package com.fotoframe.source

import android.content.Context
import android.util.Log
import com.fotoframe.data.db.Photo
import com.fotoframe.data.prefs.SettingsStore
import com.fotoframe.engine.rethrowIfCancelled
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Фотографии из папки на сетевом хранилище.
 *
 * Используется smbj — современный клиент SMB2/SMB3. Старый SMB1 не
 * поддерживается сознательно: он медленный, отключён по умолчанию в свежих
 * прошивках NAS и небезопасен.
 *
 * Про скорость. Главные потери на сетевых источниках дают две вещи:
 * переоткрытие соединения под каждый файл и мелкие порции чтения. Поэтому
 * здесь соединение и сессия живут между операциями, а буферы чтения подняты
 * до мегабайта. Плюс работает общий для приложения механизм: следующий кадр
 * скачивается заранее, пока показывается текущий.
 *
 * Про диск. Скачанные для показа файлы складываются в собственный кэш с
 * именем по хэшу пути: повторный показ и листание истории обходятся без
 * сети, а размер кэша ограничен — старые файлы вытесняются. Детектор лиц
 * в этот кэш не ходит: он читает файл в память через [readFile], иначе
 * пачка из шестидесяти снимков по десять мегабайт вымывала из кэша то,
 * что только что показывалось.
 *
 * Про потоки. К источнику одновременно обращаются показ, индексация и
 * дообработка. Подключение защищено замком: без него два вызова разом
 * открывали два соединения. Открытая шара, которой кто-то пользуется,
 * никогда не закрывается: обход папок держит её минутами, и когда показ
 * переподключался из-за одного таймаута, обход получал «PipeShare has
 * already been closed» на всех оставшихся папках. Теперь операции
 * считаются ([inFlight]), а сменённое соединение закрывается, когда
 * последняя из них завершится.
 *
 * Про диагностику. Причина неудачного подключения сохраняется в [lastError]
 * и пишется в logcat под тегом SmbSource.
 */
class SmbSource(
    context: Context,
    private val settings: SettingsStore
) : MediaSource {

    override val id = "smb"
    override val title = "Сетевая папка"

    /** Человекочитаемая причина последней неудачи подключения. */
    @Volatile
    var lastError: String? = null
        private set

    private val cacheDir = File(context.cacheDir, "smb").apply { mkdirs() }

    init {
        // Мусор от прежних версий и от прерванных загрузок.
        context.cacheDir
            .listFiles { f -> f.isFile && f.name.startsWith("smb_") }
            ?.forEach { it.delete() }
        cacheDir
            .listFiles { f -> f.isFile && f.name.endsWith(".part") }
            ?.forEach { it.delete() }
    }

    private val config: SmbConfig = SmbConfig.builder()
        // Минута на запрос: перечисление папки с тысячами файлов на
        // NAS с медленным диском не укладывалось в двадцать секунд.
        .withTimeout(60, TimeUnit.SECONDS)
        .withSoTimeout(90, TimeUnit.SECONDS)
        // Крупные буферы: на фотографиях в несколько мегабайт разница
        // с настройками по умолчанию заметна на глаз.
        .withReadBufferSize(1024 * 1024)
        .withWriteBufferSize(1024 * 1024)
        .withMultiProtocolNegotiate(true)
        // DFS домашнему NAS не нужен, а его разрешение путей после
        // сетевой заминки давало «Cannot resolve path» на обычных папках.
        .withDfsEnabled(false)
        .build()

    private val connectLock = Mutex()

    @Volatile private var client: SMBClient? = null
    @Volatile private var share: DiskShare? = null
    @Volatile private var openedFor: String? = null

    /**
     * Соединение надо пересоздать. Ставится, когда транспорт действительно
     * умер (сокет закрыт, «transport is disconnected»): после перезагрузки
     * NAS библиотека ещё какое-то время считает сессию живой, и все
     * запросы через неё падают, пока не переподключишься. Таймаут одного
     * запроса сюда не относится — соединение при нём живое.
     */
    @Volatile private var reconnectNeeded = false

    /** Сколько операций сейчас держат [share]. Пока не ноль, старую шару не закрываем. */
    private val inFlight = AtomicInteger(0)

    /** Сменённые соединения, которые ещё кто-то использует. Закрываются, когда [inFlight] обнулится. */
    private val retired = ArrayList<Pair<SMBClient, DiskShare>>()

    private suspend fun conf() = settings.settings.first()

    /**
     * Адрес мог быть введён как ссылка на веб-интерфейс хранилища.
     * Отрезаем схему, порт, путь и слэши — библиотеке нужно голое имя хоста.
     */
    private fun cleanHost(raw: String): String =
        raw.trim()
            .substringAfter("://")
            .trim('/', '\\')
            .substringBefore('/')
            .substringBefore('\\')
            .substringBefore(':')
            .trim()

    /**
     * Открывает шару и переиспользует её при следующих обращениях.
     * Ключ — комбинация хоста, шары и пользователя: сменились настройки,
     * соединение пересоздаётся. Один вызов за раз.
     */
    private suspend fun share(): DiskShare? = connectLock.withLock {
        withContext(Dispatchers.IO) {
            val c = conf()
            val host = cleanHost(c.smbHost)
            val shareName = c.smbShare.trim().trim('/', '\\')

            if (host.isBlank() || shareName.isBlank()) {
                lastError = "Не заданы адрес хранилища или имя общей папки"
                return@withContext null
            }

            val key = "$host|$shareName|${c.smbUser}"
            val existing = share
            if (existing != null && existing.isConnected && openedFor == key && !reconnectNeeded) {
                return@withContext existing
            }
            reconnectNeeded = false

            retireCurrent()

            var cl: SMBClient? = null
            try {
                Log.i(TAG, "Подключение к \\\\$host\\$shareName как '${c.smbUser}'")

                cl = SMBClient(config)
                val connection = cl.connect(host)
                val auth = if (c.smbUser.isBlank()) {
                    AuthenticationContext.anonymous()
                } else {
                    AuthenticationContext(
                        c.smbUser.trim(),
                        c.smbPassword.toCharArray(),
                        c.smbDomain.ifBlank { null }
                    )
                }
                val session = connection.authenticate(auth)
                val disk = session.connectShare(shareName) as DiskShare

                client = cl
                share = disk
                openedFor = key
                lastError = null
                Log.i(TAG, "Подключено: ${disk.smbPath}")
                disk
            } catch (e: Throwable) {
                // Ловим именно Throwable: на части прошивок нехватка классов
                // проявляется как Error, а не Exception, и без этого причина
                // терялась бы полностью. Но отмена корутины — не ошибка
                // подключения, и в lastError ей не место.
                runCatching { cl?.close() }
                e.rethrowIfCancelled()
                lastError = describe(e)
                Log.e(TAG, "Не удалось подключиться к \\\\$host\\$shareName", e)
                null
            }
        }
    }

    /** Короткое объяснение вместо голого имени класса исключения. */
    private fun describe(e: Throwable): String {
        val cause = generateSequence(e) { it.cause }.last()
        val text = listOfNotNull(e.message, cause.message.takeIf { it != e.message })
            .joinToString(" / ")
            .ifBlank { cause::class.java.simpleName }

        val hint = when {
            text.contains("STATUS_LOGON_FAILURE", true) ||
                text.contains("STATUS_ACCESS_DENIED", true) ->
                "Проверьте имя пользователя и пароль."
            text.contains("STATUS_BAD_NETWORK_NAME", true) ->
                "Такой общей папки на хранилище нет — проверьте её имя."
            text.contains("NoClassDefFound", true) || cause is NoClassDefFoundError ->
                "Библиотеке SMB не хватает классов на этой прошивке."
            text.contains("timed out", true) || text.contains("timeout", true) ->
                "Хранилище не отвечает на порт 445."
            text.contains("ENCRYPT", true) || text.contains("signing", true) ->
                "Хранилище требует шифрование SMB3 — переключите его в режим «авто»."
            else -> null
        }

        return listOfNotNull("${cause::class.java.simpleName}: $text", hint).joinToString(" ")
    }

    /**
     * Снять текущее соединение. Если им никто не пользуется — закрыть
     * сразу, иначе отложить до конца последней операции.
     */
    private fun retireCurrent() {
        val c = client
        val s = share
        client = null
        share = null
        openedFor = null
        if (c == null || s == null) {
            runCatching { s?.close() }
            runCatching { c?.close() }
            return
        }
        if (inFlight.get() == 0) {
            runCatching { s.close() }
            runCatching { c.close() }
        } else {
            synchronized(retired) { retired += c to s }
        }
    }

    private fun closeRetired() {
        val list = synchronized(retired) {
            val copy = retired.toList()
            retired.clear()
            copy
        }
        for ((c, s) in list) {
            runCatching { s.close() }
            runCatching { c.close() }
        }
    }

    /** Операция начала работу с шарой. Парный вызов — [release]. */
    private fun acquire() {
        inFlight.incrementAndGet()
    }

    private fun release() {
        if (inFlight.decrementAndGet() == 0) closeRetired()
    }

    /**
     * Ошибка операции: если транспорт умер, следующее обращение
     * переподключится. Таймаут отдельного запроса, «нет такого пути»,
     * «нет прав» — соединение не трогают.
     */
    private fun noteFailure(e: Throwable) {
        val chain = generateSequence(e) { it.cause }.toList()
        val text = chain.joinToString(" ") { it.message.orEmpty() }
        val dead = text.contains("transport is disconnected", ignoreCase = true) ||
            text.contains("already been closed", ignoreCase = true) ||
            chain.any {
                it is java.net.SocketException ||
                    it is java.io.EOFException ||
                    it is java.net.ConnectException
            }
        if (dead) reconnectNeeded = true
    }

    override suspend fun isReady(): Boolean = share() != null

    /**
     * Стоит ли сейчас вообще обращаться к хранилищу. Дешёвая проверка для
     * фоновых проходов: когда NAS выключен, каждая попытка подключения
     * стоит до двадцати секунд, и пачка из двухсот снимков растягивалась
     * на час.
     */
    fun isDown(): Boolean = share == null && lastError != null

    /**
     * Общие папки хранилища — верхний уровень, который в обзоре выбирается
     * так же, как обычная папка. Раньше имя шары приходилось вводить с
     * пульта; библиотека SMB перечислять шары не умеет, для этого отдельный
     * запрос к службе srvsvc по RPC. Соединение здесь своё и короткое:
     * шара ещё не выбрана, кэшировать нечего.
     */
    suspend fun listShares(): List<String> = withContext(Dispatchers.IO) {
        val c = conf()
        val host = cleanHost(c.smbHost)
        if (host.isBlank()) {
            lastError = "Не задан адрес хранилища"
            return@withContext emptyList()
        }

        var cl: SMBClient? = null
        try {
            cl = SMBClient(config)
            val connection = cl.connect(host)
            val auth = if (c.smbUser.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(c.smbUser.trim(), c.smbPassword.toCharArray(), c.smbDomain.ifBlank { null })
            }
            val session = connection.authenticate(auth)
            val transport = SMBTransportFactories.SRVSVC.getTransport(session)
            val shares = ServerService(transport).getShares0()
            lastError = null
            shares.map { it.netName }
                // Служебные (IPC$, ADMIN$, C$) и скрытые — не для фотографий.
                .filter { it.isNotBlank() && !it.endsWith("$") }
                .sorted()
        } catch (e: Throwable) {
            e.rethrowIfCancelled()
            lastError = describe(e)
            Log.e(TAG, "Не удалось получить список общих папок \\\\$host", e)
            emptyList()
        } finally {
            runCatching { cl?.close() }
        }
    }

    override suspend fun listFolders(parent: String?): List<Folder> =
        withContext(Dispatchers.IO) {
            val disk = share() ?: return@withContext emptyList()
            val path = parent.orEmpty().trim('/', '\\')

            acquire()
            try {
                disk.list(path)
                    .filter { it.isDirectory() && it.fileName !in setOf(".", "..") }
                    .map { Folder(path = joinPath(path, it.fileName), name = it.fileName) }
            } catch (e: Throwable) {
                e.rethrowIfCancelled()
                noteFailure(e)
                lastError = describe(e)
                Log.e(TAG, "Не удалось прочитать папку '$path'", e)
                emptyList()
            } finally {
                release()
            }
        }

    override suspend fun scan(root: String, onBatch: suspend (List<RemoteItem>) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val disk = share() ?: return@withContext false

            val queue = ArrayDeque<String>()
            queue += root.trim('/', '\\')
            val batch = ArrayList<RemoteItem>(BATCH)
            var failedDirs = 0

            acquire()
            try {
            while (queue.isNotEmpty()) {
                val dir = queue.removeFirst()
                val entries = try {
                    disk.list(dir)
                } catch (e: Throwable) {
                    e.rethrowIfCancelled()
                    noteFailure(e)
                    // Папку не прочитали — обход считается неполным, и
                    // Indexer не станет удалять её содержимое из индекса.
                    failedDirs++
                    Log.w(TAG, "Не удалось прочитать папку '$dir': ${e.message}")
                    continue
                }

                for (entry in entries) {
                    val name = entry.fileName
                    if (name == "." || name == "..") continue

                    val full = joinPath(dir, name)

                    if (entry.isDirectory()) {
                        queue += full
                        continue
                    }
                    if (!isImage(name)) continue

                    batch += RemoteItem(
                        remoteId = full,
                        uri = full,
                        displayName = name,
                        albumName = dir.substringAfterLast('\\').ifBlank { null },
                        // Время записи файла — то, что даёт SMB сразу. Это
                        // дата копирования, не съёмки; настоящую допишет
                        // PhotoEnricher из EXIF, а до тех пор подпись даты
                        // на экране не показывается.
                        takenAt = entry.lastWriteTime.toEpochMillis(),
                        takenAtExact = false,
                        sizeBytes = entry.endOfFile
                    )

                    if (batch.size >= BATCH) {
                        onBatch(batch.toList())
                        batch.clear()
                    }
                }
            }

            if (batch.isNotEmpty()) onBatch(batch.toList())
            if (failedDirs > 0) Log.w(TAG, "Обход неполный: пропущено папок $failedDirs")
            failedDirs == 0
            } finally {
                release()
            }
        }

    /**
     * Файл с шары скачивается в собственный кэш и отдаётся загрузчику как
     * локальный. Coil не умеет ходить по smb:// сам, а держать поток открытым
     * между кадрами ненадёжно.
     */
    override suspend fun resolveDisplayUrl(photo: Photo): String =
        withContext(Dispatchers.IO) {
            val cached = File(cacheDir, cacheName(photo.remoteId))
            if (cached.length() > 0) {
                cached.setLastModified(System.currentTimeMillis())
                return@withContext "file://${cached.absolutePath}"
            }

            val disk = share() ?: throw IOException(lastError ?: "Сетевая папка недоступна")

            // Временное имя уникальное: показ и дообработка могут качать один
            // и тот же файл одновременно, и общий .part давал битую картинку.
            val tmp = File.createTempFile("dl_", ".part", cacheDir)
            acquire()
            try {
                openForRead(disk, photo.uri).use { remote ->
                    remote.inputStream.use { input ->
                        tmp.outputStream().use { output ->
                            input.copyTo(output, DEFAULT_BUFFER)
                        }
                    }
                }
                if (!tmp.renameTo(cached)) {
                    tmp.copyTo(cached, overwrite = true)
                    tmp.delete()
                }
                prune()
                "file://${cached.absolutePath}"
            } catch (e: Throwable) {
                tmp.delete()
                if (e !is kotlinx.coroutines.CancellationException) noteFailure(e)
                throw e
            } finally {
                release()
            }
        }

    /**
     * Перенос файла в корзину рамки вместо удаления.
     *
     * Безвозвратно не удаляем намеренно: кнопка на пульте — слишком
     * лёгкий способ потерять снимок. Файл переезжает в [TRASH_DIR] в
     * корне шары, откуда его видно с компьютера и можно вернуть.
     * При совпадении имён к имени добавляется номер.
     */
    override suspend fun delete(photo: Photo): DeleteResult = withContext(Dispatchers.IO) {
        val disk = share() ?: return@withContext DeleteResult.Failed(
            lastError ?: "Сетевая папка недоступна"
        )
        val src = photo.uri.trim('/', '\\')

        acquire()
        try {
            if (!disk.folderExists(TRASH_DIR)) disk.mkdir(TRASH_DIR)

            val name = src.substringAfterLast('\\')
            var target = "$TRASH_DIR\\$name"
            if (disk.fileExists(target)) {
                val stem = name.substringBeforeLast('.', name)
                val ext = name.substringAfterLast('.', "")
                val suffix = if (ext.isEmpty()) "" else ".$ext"
                var n = 1
                while (disk.fileExists(target) && n < 1000) {
                    target = "$TRASH_DIR\\$stem ($n)$suffix"
                    n++
                }
            }

            // rename с путём внутри той же шары — это и есть перенос.
            disk.openFile(
                src,
                EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            ).use { it.rename(target) }

            DeleteResult.Done("перенесён в папку «$TRASH_DIR» на сетевом хранилище")
        } catch (e: Throwable) {
            e.rethrowIfCancelled()
            noteFailure(e)
            Log.w(TAG, "Не удалось убрать '$src': ${e.message}")
            DeleteResult.Failed(describe(e))
        } finally {
            release()
        }
    }

    /**
     * Начало файла без скачивания целиком.
     *
     * EXIF в JPEG лежит в самом начале, до данных изображения: сегмент
     * APP1 по стандарту не превышает 64 КБ, так что ста двадцати восьми
     * хватает с запасом даже вместе с миниатюрой. Разница решающая:
     * снимок с телефона весит 5–15 МБ, и качать всю коллекцию ради двух
     * полей — часы работы и десятки гигабайт трафика.
     */
    suspend fun readHeader(path: String, limit: Int = HEADER_BYTES): ByteArray? =
        withContext(Dispatchers.IO) {
            val disk = share() ?: return@withContext null

            acquire()
            try {
                openForRead(disk, path).use { remote ->
                    // Буфер по размеру файла, а не по потолку: для второй
                    // попытки потолок — 24 МБ, и выделять их ради снимка
                    // в 3 МБ на приставке с гигабайтом памяти незачем.
                    val size = runCatching {
                        remote.fileInformation.standardInformation.endOfFile
                    }.getOrDefault(limit.toLong())
                    val want = minOf(limit.toLong(), size).toInt().coerceAtLeast(1)

                    val buffer = ByteArray(want)
                    val chunk = ByteArray(minOf(CHUNK, want))
                    var filled = 0
                    while (filled < want) {
                        val read = remote.read(chunk, filled.toLong(), 0, minOf(chunk.size, want - filled))
                        if (read <= 0) break
                        System.arraycopy(chunk, 0, buffer, filled, read)
                        filled += read
                    }
                    if (filled == 0) null else if (filled == want) buffer else buffer.copyOf(filled)
                }
            } catch (e: Throwable) {
                e.rethrowIfCancelled()
                noteFailure(e)
                Log.w(TAG, "Не удалось прочитать заголовок '$path': ${e.message}")
                null
            } finally {
                release()
            }
        }

    /**
     * Файл целиком в память, минуя кэш показа. Нужен детектору лиц:
     * ему хватает одного прочтения, а место в кэше дороже.
     */
    suspend fun readFile(path: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val disk = share() ?: return@withContext null
            acquire()
            try {
                openForRead(disk, path).use { remote ->
                    remote.inputStream.use { it.readBytes() }
                }
            } catch (e: Throwable) {
                e.rethrowIfCancelled()
                noteFailure(e)
                Log.w(TAG, "Не удалось прочитать файл '$path': ${e.message}")
                null
            } finally {
                release()
            }
        }

    private fun openForRead(disk: DiskShare, path: String): SmbFile =
        disk.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )

    /** Держим кэш в пределах лимита, вытесняя самые давние файлы. */
    private fun prune() {
        val files = cacheDir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_LIMIT_BYTES) return

        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_LIMIT_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    private fun cacheName(remoteId: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(remoteId.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".img"
    }

    private fun FileIdBothDirectoryInformation.isDirectory(): Boolean =
        (fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L

    private fun joinPath(parent: String, child: String): String =
        if (parent.isBlank()) child else "$parent\\$child"

    private fun isImage(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".heic") || lower.endsWith(".heif")
    }

    companion object {
        /** Корзина рамки в корне шары. Видна с компьютера, файл можно вернуть. */
        const val TRASH_DIR = "_Корзина фоторамки"

        private const val TAG = "SmbSource"
        private const val BATCH = 300
        private const val DEFAULT_BUFFER = 256 * 1024
        private const val CACHE_LIMIT_BYTES = 400L * 1024 * 1024
        private const val HEADER_BYTES = 128 * 1024
        private const val CHUNK = 64 * 1024
    }
}
