package com.fotoframe.engine

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fotoframe.App
import com.fotoframe.data.db.Photo
import com.fotoframe.data.db.PhotoDao
import com.fotoframe.data.prefs.SettingsStore
import com.fotoframe.source.MediaSource
import com.fotoframe.source.RemoteItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Наполнение индекса.
 *
 * Обход источника и показ слайдшоу разведены намеренно: рамка крутит кадры
 * по локальной базе и не ждёт сеть, а обновление списка идёт в фоне.
 * Отсюда же берётся firstSeenAt — момент, когда снимок появился в индексе.
 * Именно он делает возможным пул новинок в [PhotoPicker].
 *
 * Про удаление. Файлы, исчезнувшие из источника, убираются из индекса,
 * иначе рамка спотыкается на битых ссылках. Но удаление уносит историю
 * показов, лица и города, а воскресший файл возвращается «новинкой».
 * Поэтому удаляем только по полному обходу и только если исчезло не
 * слишком много: отвалившаяся на минуту папка или NAS, ответивший пустым
 * списком, на удаление не тянут.
 *
 * У защиты два исключения. Первое — смена папки: пользователь выбрал на
 * Диске другой каталог, и записи прежнего исчезают тысячами вполне законно.
 * Корень последнего полного обхода хранится в настройках, и если он
 * отличается от текущего, удаление идёт без оглядки на долю. Второе —
 * ручной запуск с флагом [force]: кнопка «удалить пропавшие» в настройках.
 */
class Indexer(
    private val dao: PhotoDao,
    private val settings: SettingsStore
) {

    suspend fun index(source: MediaSource, root: String, force: Boolean = false): Result {
        if (!source.isReady()) {
            return Result(added = 0, skipped = 0, error = "Источник не готов: ${source.title}")
        }

        val now = System.currentTimeMillis()
        val rootChanged = settings.indexedRoot(source.id).let { it != null && it != root }
        val known = dao.knownRemoteIds(source.id).toHashSet()
        // Ссылки на превью: если источник стал отдавать другой размер,
        // старые записи получают новую ссылку без переиндексации.
        val thumbs = HashMap<String, String?>()
        dao.knownThumbnails(source.id).forEach { thumbs[it.remoteId] = it.thumbnailUrl }
        // Записи, у которых ещё нет настоящей даты съёмки: если источник
        // теперь её знает — дописываем, не переиндексируя.
        val needDate = dao.remoteIdsWithoutExactDate(source.id).toHashSet()
        val seen = HashSet<String>(known.size)
        var added = 0
        var skipped = 0

        return try {
            val complete = source.scan(root) { batch ->
                val fresh = batch.filterNot { known.contains(it.remoteId) }
                batch.forEach { seen += it.remoteId }
                skipped += batch.size - fresh.size

                if (fresh.isNotEmpty()) {
                    dao.insertAll(fresh.map { it.toPhoto(source.id, now) })
                    added += fresh.size
                }

                if (needDate.isNotEmpty()) {
                    batch.asSequence()
                        .filter { it.takenAtExact && needDate.contains(it.remoteId) }
                        .forEach {
                            dao.setTakenAtIfNotExact(source.id, it.remoteId, it.takenAt)
                            needDate -= it.remoteId
                        }
                }

                for (item in batch) {
                    if (item.remoteId in known && thumbs.containsKey(item.remoteId) &&
                        thumbs[item.remoteId] != item.thumbnailUrl
                    ) {
                        dao.setThumbnail(source.id, item.remoteId, item.thumbnailUrl)
                        thumbs[item.remoteId] = item.thumbnailUrl
                    }
                }
            }

            val gone = known - seen
            var removed = 0
            var note: String? = null

            if (gone.isNotEmpty()) {
                val suspicious = gone.size > REMOVE_GUARD_MIN &&
                    gone.size > known.size * REMOVE_GUARD_SHARE
                note = when {
                    !complete ->
                        "обход неполный, удаление ${gone.size} записей отложено"
                    suspicious && !force && !rootChanged ->
                        "исчезло ${gone.size} из ${known.size} — похоже на сбой, удаление отложено"
                    else -> {
                        gone.chunked(400).forEach { dao.deleteByRemoteIds(source.id, it) }
                        removed = gone.size
                        null
                    }
                }
                if (note != null) Log.w(TAG, "${source.id}: $note")
            }

            if (complete) settings.setIndexedRoot(source.id, root)

            Result(added = added, skipped = skipped, removed = removed, note = note)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обхода ${source.id}", e)
            Result(added = added, skipped = skipped, error = e.message ?: "неизвестная ошибка")
        }
    }

    private fun RemoteItem.toPhoto(sourceId: String, now: Long): Photo {
        // Источник даты съёмки не знает (так у сетевой папки — там время
        // записи файла), но имя почти всегда её содержит. Это бесплатно и
        // избавляет от ожидания сплошного прохода по EXIF.
        val fromName = if (takenAtExact) null else FileNameDate.parse(displayName)

        return Photo(
        sourceId = sourceId,
        remoteId = remoteId,
        uri = uri,
        displayName = displayName,
        albumName = albumName,
        takenAt = fromName ?: takenAt,
        takenAtExact = takenAtExact || fromName != null,
        firstSeenAt = now,
        width = width,
        height = height,
        sizeBytes = sizeBytes,
        thumbnailUrl = thumbnailUrl,
        latitude = latitude,
        longitude = longitude
        )
    }

    data class Result(
        val added: Int,
        val skipped: Int,
        val removed: Int = 0,
        /** Обход прошёл, но с оговоркой — например, удаление отложено. */
        val note: String? = null,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null

        /** Строка для отчёта в настройках. */
        fun describe(): String =
            if (!ok) error!!
            else buildString {
                append("добавлено $added")
                if (removed > 0) append(", удалено $removed")
                if (note != null) append(" ($note)")
            }
    }

    private companion object {
        const val TAG = "Indexer"

        /** Меньше этого удаляем без вопросов — обычная уборка. */
        const val REMOVE_GUARD_MIN = 50

        /** Больше этой доли коллекции за один обход — подозрительно. */
        const val REMOVE_GUARD_SHARE = 0.2
    }
}

/**
 * Периодическое обновление индекса в фоне.
 *
 * Про время. У воркера есть десять минут, потом система его останавливает,
 * а WorkManager считает работу прерванной и запускает заново — сразу же.
 * Дообработка на большой коллекции идёт часами, и без ограничения это
 * превращалось в бесконечный цикл: полный обход NAS каждые десять минут,
 * перезапуски процесса, гонка с показом за соединение. Поэтому воркер
 * укладывается в [BUDGET_MS] сам: дообработке достаётся остаток, а обход
 * источников не повторяется, если прошёл меньше часа назад.
 */
class IndexWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val startedAt = System.currentTimeMillis()
        val app = applicationContext as App
        val dao = app.database.photoDao()
        val settings = app.settingsStore
        val indexer = Indexer(dao, settings)
        val current = settings.settings.first()

        if (startedAt - settings.lastIndexAt() >= MIN_GAP_MS) {
            val results = ArrayList<String>()

            results += "local: " + indexer.index(app.sources.local, "*").describe()

            if (current.yandexToken != null) {
                results += "yandex: " + indexer.index(app.sources.yandex, current.yandexFolder).describe()
            }

            if (current.smbHost.isNotBlank() && current.smbShare.isNotBlank()) {
                results += "smb: " + indexer.index(app.sources.smb, current.smbFolder).describe()
            }
            Log.i(TAG, "Фоновое обновление: " + results.joinToString("; "))

            // Новые записи входят в индекс допущенными; фильтр надо применить,
            // иначе до ручного обновления на экран попадали бы и скриншоты.
            tryOrNull { ContentFilter(dao).apply(current) }
            settings.setLastIndexAt(System.currentTimeMillis())
        } else {
            Log.i(TAG, "Обход пропущен: индекс обновлялся меньше часа назад")
        }

        // Досчитываем даты, координаты, города и лица — сколько успеем
        // в оставшееся время. Проходы инкрементальные, продолжатся в
        // следующий раз.
        val left = BUDGET_MS - (System.currentTimeMillis() - startedAt)
        if (left > 30_000L) {
            val enricher = PhotoEnricher(app, dao, app.sources)
            withTimeoutOrNull(left) { tryOrNull { enricher.enrichAll() } }
            Log.i(TAG, "Дообработка: время вышло или очередь пуста")
        }

        // Всегда success: недоступный источник — обычное дело для рамки
        // (NAS выключили на ночь), и retry с растущей паузой только
        // сдвигал бы следующий плановый обход.
        return androidx.work.ListenableWorker.Result.success()
    }

    companion object {
        private const val NAME = "index_photos"
        private const val TAG = "IndexWorker"

        /** Во столько укладывается один запуск — с запасом до системного лимита в 10 минут. */
        private const val BUDGET_MS = 8L * 60 * 1000

        /** Чаще этого обход источников не повторяется. */
        private const val MIN_GAP_MS = 60L * 60 * 1000

        fun schedule(context: Context) {
            // Без требования сети: рамка с одной USB-флешкой иначе никогда
            // не обновляла бы индекс в фоне. Сетевые источники сами
            // сообщат об ошибке, если сети нет.
            val request = PeriodicWorkRequestBuilder<IndexWorker>(6, TimeUnit.HOURS)
                .build()

            // UPDATE, а не KEEP: изменённые здесь интервал или ограничения
            // применяются без переустановки приложения.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
