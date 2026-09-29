package com.fotoframe.source

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.messages.SMB2ReadResponse
import com.hierynomus.smbj.share.File as SmbFile
import com.hierynomus.smbj.share.SmbPipeline
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Чтение ролика с сетевой папки — с упреждением, как у Kodi.
 *
 * Плеер просит данные мелко, часто по десяткам килобайт. Если каждый
 * такой кусок превращать в отдельный запрос по сети с ожиданием ответа,
 * задержка Wi-Fi съедает большую часть скорости: 4K-ролик с телефона не
 * успевает подгружаться, и плеер играет несколько секунд, встаёт на
 * буферизацию, снова играет. Здесь отдельный поток непрерывно читает файл
 * крупными кусками по [CHUNK] и держит до [AHEAD_CHUNKS] кусков впереди
 * плеера, а плеер забирает данные уже из памяти.
 *
 * Перемотка — это закрытие и новое открытие с другой позиции: старый
 * поток упреждения останавливается, новый начинает с нового места.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class SmbDataSource(private val smb: SmbSource) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var file: SmbFile? = null
    private var reader: Prefetcher? = null

    /** Кусок, из которого сейчас отдаются данные, и позиция в нём. */
    private var current: ByteArray? = null
    private var currentPos = 0
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val path = Uri.decode(dataSpec.uri.toString().substringAfter("://"))
        val (f, length) = smb.openStream(path) ?: throw IOException("Не удалось открыть ролик на сетевой папке")
        file = f
        val start = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else length - start
        if (remaining < 0) throw IOException("Позиция за концом файла")
        reader = Prefetcher(f, start, start + remaining, path.substringAfterLast('\\')).also { it.start() }
        current = null
        currentPos = 0
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        var chunk = current
        if (chunk == null || currentPos >= chunk.size) {
            chunk = reader?.take() ?: throw IOException("Ролик не открыт")
            if (chunk.isEmpty()) return C.RESULT_END_OF_INPUT
            current = chunk
            currentPos = 0
        }

        val n = minOf(length, chunk.size - currentPos, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        System.arraycopy(chunk, currentPos, buffer, offset, n)
        currentPos += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        reader?.shutdown()
        reader = null
        current = null
        val f = file
        file = null
        uri = null
        if (f != null) {
            smb.closeStream(f)
            transferEnded()
        }
    }

    /**
     * Поток упреждающего чтения: читает [from]..[until] кусками и кладёт в
     * очередь. Конец файла — пустой кусок; ошибка — тоже пустой кусок плюс
     * запомненное исключение, которое бросится в [take].
     */
    private class Prefetcher(
        private val file: SmbFile,
        from: Long,
        private val until: Long,
        private val name: String
    ) : Thread("smb-prefetch") {

        // Куски бывают и по 64 КБ (так договаривается часть NAS), и по
        // мегабайту — ёмкость очереди считаем так, чтобы впереди было
        // около [AHEAD_BYTES] в любом случае.
        private val queue = ArrayBlockingQueue<ByteArray>(
            (AHEAD_BYTES / SmbPipeline.maxRead(file).coerceIn(16 * 1024, CHUNK)).coerceIn(8, 2048)
        )
        private var pos = from

        @Volatile
        private var stopped = false

        /** Сколько плеер уже забрал — от этого растёт упреждение. */
        @Volatile
        private var consumed = 0L

        @Volatile
        private var error: IOException? = null

        init {
            isDaemon = true
        }

        override fun run() {
            val started = System.nanoTime()
            var total = 0L
            var logged = 0L
            try {
                // Сколько сервер отдаёт за один запрос, и сколько запросов
                // держать в полёте: пока ждём ответ на первый, уже отправлены
                // следующие — так задержка сети перестаёт ограничивать
                // скорость. Раньше запросы шли строго по одному, и при
                // задержке Wi-Fi около 100 мс скорость падала до 0,7 МБ/с.
                val req = SmbPipeline.maxRead(file).coerceIn(16 * 1024, CHUNK)
                val window = (IN_FLIGHT_BYTES / req).coerceIn(4, 64)
                Log.i(TAG, "SMB «$name»: запрос до ${req / 1024} КБ, в полёте $window")

                val inFlight = ArrayDeque<Pair<Long, java.util.concurrent.Future<SMB2ReadResponse>>>()
                val base = pos
                var next = pos

                // Упреждение нарастает: сначала немного впереди, и чем дальше
                // плеер действительно идёт по файлу, тем больше впрок — до
                // [AHEAD_BYTES]. Плеер нередко открывает ролик и тут же
                // прыгает (у MP4 с телефона оглавление в конце файла); при
                // полном упреждении с первого байта каждое такое открытие
                // тратило десятки секунд на ненужные мегабайты.
                fun allowedAhead(): Long = minOf(AHEAD_BYTES.toLong(), START_AHEAD + consumed * 2)

                fun refill() {
                    while (!stopped && inFlight.size < window && next < until &&
                        next - base - consumed < allowedAhead()
                    ) {
                        val len = minOf(req.toLong(), until - next).toInt()
                        inFlight.addLast(next to SmbPipeline.readAsync(file, next, len))
                        next += len
                    }
                }
                refill()

                while (!stopped) {
                    if (inFlight.isEmpty()) {
                        if (next >= until) break
                        // Упреждение упёрлось в разрешённое — ждём, пока плеер заберёт.
                        Thread.sleep(50)
                        refill()
                        continue
                    }
                    val (offset, future) = inFlight.removeFirst()
                    val resp = future.get(60, TimeUnit.SECONDS)
                    val status = resp.header.statusCode
                    if (status == NtStatus.STATUS_END_OF_FILE.value) break
                    if (status != NtStatus.STATUS_SUCCESS.value) {
                        throw IOException("SMB: статус 0x${java.lang.Long.toHexString(status)}")
                    }
                    var data = resp.data
                    val expected = minOf(req.toLong(), until - offset).toInt()
                    // Сервер отдал меньше, чем просили, не на конце файла, —
                    // добираем остаток обычным чтением, чтобы не было дыры.
                    if (data.size < expected) {
                        val rest = ByteArray(expected)
                        System.arraycopy(data, 0, rest, 0, data.size)
                        var filled = data.size
                        while (filled < expected && !stopped) {
                            val n = file.read(rest, offset + filled, filled, expected - filled)
                            if (n <= 0) break
                            filled += n
                        }
                        data = if (filled == expected) rest else rest.copyOf(filled)
                    }
                    if (data.isEmpty()) break

                    pos = offset + data.size
                    total += data.size
                    refill()

                    // Скорость — в лог каждые 32 МБ: по ней видно, успевает
                    // ли сеть за роликом.
                    if (total - logged >= (if (logged == 0L) 4L else 32L) * 1024 * 1024) {
                        logged = total
                        val sec = (System.nanoTime() - started) / 1e9
                        Log.i(TAG, "SMB «$name»: %.1f МБ/с (%.0f Мбит/с)".format(
                            total / 1048576.0 / sec, total * 8 / 1e6 / sec
                        ))
                    }

                    // Ждём места в очереди, но не вечно — чтобы заметить остановку.
                    while (!stopped && !queue.offer(data, 200, TimeUnit.MILLISECONDS)) { /* ждём */ }
                }
            } catch (e: Throwable) {
                if (!stopped) {
                    error = IOException("Чтение ролика с сетевой папки: ${e.message}", e)
                    Log.w(TAG, "SMB «$name»: ${e.message}")
                }
            } finally {
                // Итог по этому открытию: сколько прочитано и как быстро.
                val sec = (System.nanoTime() - started) / 1e9
                if (total > 0 && sec > 0.5) {
                    Log.i(TAG, "SMB «$name»: итого %.1f МБ за %.0f с — %.1f МБ/с".format(
                        total / 1048576.0, sec, total / 1048576.0 / sec
                    ))
                }
                // Сигнал конца — дожидаемся места, иначе читатель зависнет.
                while (!stopped && !queue.offer(EMPTY, 200, TimeUnit.MILLISECONDS)) { /* ждём */ }
            }
        }

        fun take(): ByteArray {
            while (true) {
                val chunk = queue.poll(500, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    if (chunk.isEmpty()) error?.let { throw it }
                    consumed += chunk.size
                    return chunk
                }
                if (stopped) return EMPTY
                if (!isAlive && queue.isEmpty()) {
                    error?.let { throw it }
                    return EMPTY
                }
            }
        }

        fun shutdown() {
            stopped = true
            queue.clear()
            runCatching { join(2_000) }
        }
    }

    private companion object {
        const val TAG = "Video"

        /** Кусок чтения: крупный, чтобы задержка сети не съедала скорость. */
        const val CHUNK = 1024 * 1024

        /** Сколько держать прочитанным впереди плеера — предел. */
        const val AHEAD_BYTES = 32 * 1024 * 1024

        /** С чего упреждение начинается при открытии. */
        const val START_AHEAD = 2L * 1024 * 1024

        /** Сколько запрашивать одновременно — «в полёте». */
        const val IN_FLIGHT_BYTES = 4 * 1024 * 1024

        val EMPTY = ByteArray(0)
    }
}

/**
 * Источник данных для видеоплеера: ролики с сетевой папки — через
 * [SmbDataSource], всё остальное (файлы устройства, ссылки Диска) —
 * стандартным источником ExoPlayer.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class RoutingDataSource(context: Context, smb: SmbSource) : DataSource {

    private val fallback = DefaultDataSource.Factory(context).createDataSource()
    private val smbSource = SmbDataSource(smb)
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        fallback.addTransferListener(transferListener)
        smbSource.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = if (dataSpec.uri.scheme == SmbSource.STREAM_SCHEME) smbSource else fallback
        active = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }

    class Factory(private val context: Context, private val smb: SmbSource) : DataSource.Factory {
        override fun createDataSource(): DataSource = RoutingDataSource(context, smb)
    }
}
