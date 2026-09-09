package com.fotoframe.source.yandex

import com.fotoframe.data.db.Photo
import com.fotoframe.data.prefs.SettingsStore
import com.fotoframe.source.Folder
import com.fotoframe.engine.rethrowIfCancelled
import com.fotoframe.source.DeleteResult
import com.fotoframe.source.MediaSource
import com.fotoframe.source.RemoteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Фотографии с Яндекс.Диска.
 *
 * Токен хранится в настройках. Получить его можно двумя путями:
 * зарегистрировать приложение на oauth.yandex.ru и пройти обычный
 * OAuth-редирект, либо — что удобнее для приставки без клавиатуры —
 * ввести токен один раз с телефона. Экран настроек поддерживает второй
 * способ; первый добавляется без изменений в этом классе.
 */
class YandexDiskSource(
    private val api: YandexApi,
    private val settings: SettingsStore
) : MediaSource {

    override val id = "yandex"
    override val title = "Яндекс.Диск"

    private suspend fun token(): String? = settings.settings.first().yandexToken

    /** Причина последней неудачи — для сообщения в обзоре папок. */
    @Volatile
    var lastError: String? = null
        private set

    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        val t = token()
        if (t == null) {
            lastError = "Токен не задан"
            return@withContext false
        }
        try {
            api.diskInfo(YandexApi.auth(t))
            lastError = null
            true
        } catch (e: retrofit2.HttpException) {
            lastError = when (e.code()) {
                401 -> "Диск не принял токен (401). Проверьте, что он введён целиком и не склеен с прежним."
                403 -> "Токену не хватает прав (403)."
                else -> "Диск ответил ${e.code()}"
            }
            false
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            lastError = "Диск недоступен: ${e.message}"
            false
        }
    }

    override suspend fun listFolders(parent: String?): List<Folder> =
        withContext(Dispatchers.IO) {
            val t = token() ?: return@withContext emptyList()
            val auth = YandexApi.auth(t)
            val path = parent ?: "disk:/"
            val out = ArrayList<Folder>()
            var offset = 0

            while (true) {
                val items = api.listResources(
                    auth = auth, path = path, limit = PAGE, offset = offset
                ).embedded?.items.orEmpty()
                if (items.isEmpty()) break
                out += items.filter { it.isDir }.map { Folder(path = it.path, name = it.name) }
                offset += items.size
                if (items.size < PAGE) break
            }
            out
        }

    override suspend fun scan(root: String, onBatch: suspend (List<RemoteItem>) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val t = token() ?: return@withContext false
            val auth = YandexApi.auth(t)

            // Любая ошибка сети уходит исключением наверх, до Indexer,
            // и тот не трогает индекс. Значит, дошли сюда — обход полный.
            if (root == "disk:/" || root.isEmpty()) {
                scanFlat(auth, onBatch)
            } else {
                scanFolder(auth, root, onBatch)
            }
            true
        }

    /**
     * Обход всего Диска плоским списком. Один запрос отдаёт до 200 файлов
     * с уже готовыми ссылками на превью — рекурсия по папкам не нужна.
     */
    private suspend fun scanFlat(auth: String, onBatch: suspend (List<RemoteItem>) -> Unit) {
        var offset = 0
        while (true) {
            val page = api.listFiles(auth = auth, limit = PAGE, offset = offset)
            if (page.items.isEmpty()) break

            val items = page.items.filter { it.isImage }
            if (items.isNotEmpty()) onBatch(items.map { it.toRemoteItem() })

            offset += page.items.size
            if (page.items.size < PAGE) break
        }
    }

    /** Рекурсивный обход конкретной папки с подпапками. */
    private suspend fun scanFolder(
        auth: String,
        path: String,
        onBatch: suspend (List<RemoteItem>) -> Unit
    ) {
        val queue = ArrayDeque<String>()
        queue += path

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            var offset = 0

            while (true) {
                val response = api.listResources(
                    auth = auth, path = current, limit = PAGE, offset = offset
                )
                val items = response.embedded?.items.orEmpty()
                if (items.isEmpty()) break

                queue += items.filter { it.isDir }.map { it.path }

                val images = items.filter { !it.isDir && it.isImage }
                if (images.isNotEmpty()) onBatch(images.map { it.toRemoteItem() })

                offset += items.size
                if (items.size < PAGE) break
            }
        }
    }

    override suspend fun resolveDisplayUrl(photo: Photo): String =
        withContext(Dispatchers.IO) {
            // Превью отдаётся мгновенно и весит в разы меньше исходника.
            photo.thumbnailUrl?.let { return@withContext it }
            downloadUrl(photo) ?: photo.uri
        }

    /**
     * В Корзину Диска. Нужен токен с правом cloud_api:disk.write — токен
     * только на чтение получит от сервера 403, и это уйдёт на экран
     * понятным текстом, а не «ошибка 403».
     */
    override suspend fun delete(photo: Photo): DeleteResult = withContext(Dispatchers.IO) {
        val t = token() ?: return@withContext DeleteResult.Failed("Токен Яндекс.Диска не задан")

        val response = try {
            api.deleteResource(YandexApi.auth(t), photo.uri)
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            return@withContext DeleteResult.Failed(e.message ?: "Диск недоступен")
        }

        when {
            // 204 — удалено, 202 — принято в обработку (большие папки).
            response.isSuccessful -> DeleteResult.Done("перемещён в Корзину Яндекс.Диска")
            response.code() == 403 -> DeleteResult.Unsupported(
                "У токена нет права на запись. Нужен токен с cloud_api:disk.write"
            )
            response.code() == 404 -> DeleteResult.Done("файла уже нет на Диске")
            else -> DeleteResult.Failed("Диск ответил ${response.code()}")
        }
    }

    /**
     * Временная ссылка на оригинал. Нужна дообработке: превью идёт без
     * EXIF, а в оригинале есть и дата, и координаты, и размеры — их
     * можно вычитать из первых 128 КБ, не скачивая файл целиком.
     */
    suspend fun downloadUrl(photo: Photo): String? =
        withContext(Dispatchers.IO) {
            val t = token() ?: return@withContext null
            api.downloadLink(YandexApi.auth(t), photo.uri).href.takeIf { it.isNotBlank() }
        }

    private fun Resource.toRemoteItem(): RemoteItem {
        val exifDate = parseDate(exif?.dateTime)
        // Без дат вовсе — 0, а не «сейчас»: иначе снимок без даты стал бы
        // самым новым в коллекции и лез бы на экран при приоритете новых.
        val taken = exifDate
            ?: parseDate(created)
            ?: parseDate(modified)
            ?: 0L

        return RemoteItem(
            remoteId = path,
            uri = path,
            displayName = name,
            albumName = path.substringBeforeLast('/').substringAfterLast('/')
                .takeIf { it.isNotBlank() && it != "disk:" },
            takenAt = taken,
            takenAtExact = exifDate != null,
            sizeBytes = size,
            thumbnailUrl = preview
        )
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        for (pattern in DATE_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(raw)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private companion object {
        const val PAGE = 200
        val DATE_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss"
        )
    }
}
