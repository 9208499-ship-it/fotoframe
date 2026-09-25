package com.fotoframe.source

import android.content.Context
import android.util.Log
import com.fotoframe.data.db.Photo
import com.fotoframe.data.db.PhotoDao
import com.fotoframe.engine.rethrowIfCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Открытые собрания музеев: картины в общественном достоянии (CC0) —
 * их можно свободно показывать, без разрешений и без обязательного
 * указания авторства. Автор, название, год и музей приходят вместе с
 * картиной и идут в подпись.
 *
 * Картинка перед показом скачивается в кэш и отдаётся файлом, как у
 * сетевой папки: так разбор размеров, поиск лиц и кадрирование работают
 * без изменений.
 *
 * Выбор музеев проверен из сети в России (сентябрь 2026): Метрополитен
 * и Кливленд доступны целиком, у Чикагского института искусств закрыт
 * сервер изображений.
 */
/** Идентификаторы музейных источников. */
val MUSEUM_SOURCES = setOf("met", "cleveland")

abstract class MuseumSource(
    context: Context,
    protected val http: OkHttpClient,
    /** Нужен, чтобы дописать подпись картины, когда она узнаётся при показе. */
    private val daoProvider: () -> PhotoDao
) : MediaSource {

    /** Название музея для подписи. */
    abstract val museum: String

    private val cacheDir = File(context.cacheDir, "museum").apply { mkdirs() }

    override suspend fun isReady(): Boolean = true

    override suspend fun listFolders(parent: String?): List<Folder> = emptyList()

    override suspend fun delete(photo: Photo): DeleteResult =
        DeleteResult.Unsupported("Картины музеев удалить нельзя — их можно скрыть")

    /** Прямая ссылка на изображение и подпись; null — картины нет или она не свободна. */
    protected abstract suspend fun details(photo: Photo): Pair<String, String?>?

    override suspend fun resolveDisplayUrl(photo: Photo): String = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "${id}_${photo.remoteId}.jpg")
        if (file.length() > 0) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext "file://${file.absolutePath}"
        }

        val (imageUrl, caption) = details(photo)
            ?: run {
                // Не в общественном достоянии или без картинки — в показ
                // больше не просится.
                runCatching { daoProvider().deleteById(photo.id) }
                throw IllegalStateException("Картина ${photo.remoteId} недоступна")
            }

        if (caption != null && caption != photo.displayName) {
            runCatching { daoProvider().setDisplayName(photo.id, caption) }
        }

        val tmp = File.createTempFile("dl_", ".part", cacheDir)
        try {
            http.newCall(
                Request.Builder().url(imageUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                resp.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: throw IllegalStateException("пустой ответ")
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            prune()
            "file://${file.absolutePath}"
        } catch (e: Throwable) {
            tmp.delete()
            e.rethrowIfCancelled()
            Log.w(TAG, "$museum: не удалось скачать картину ${photo.remoteId}: ${e.message}")
            throw e
        }
    }

    /** Кэш картин — не больше [CACHE_LIMIT], вытесняются давние. */
    private fun prune() {
        val files = cacheDir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= CACHE_LIMIT) break
            total -= f.length()
            f.delete()
        }
    }

    protected fun getJson(url: String): JsonObject? = try {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            .execute()
            .use { r -> if (r.isSuccessful) r.body?.string()?.let { Json.parseToJsonElement(it) as? JsonObject } else null }
    } catch (e: Exception) {
        e.rethrowIfCancelled()
        Log.w(TAG, "$museum: ${url.substringAfter("//").substringBefore('?')} — ${e.message}")
        null
    }

    protected fun JsonObject.str(k: String) =
        (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    /** «Ван Гог — Пшеничное поле с кипарисами, 1889». */
    protected fun caption(artist: String?, title: String?, date: String?): String =
        listOfNotNull(
            listOfNotNull(artist, title).joinToString(" — ").takeIf { it.isNotBlank() },
            date
        ).joinToString(", ").ifBlank { museum }

    protected companion object {
        const val TAG = "Museum"
        const val USER_AGENT = "FotoFrame (Android TV photo frame; github.com/9208499-ship-it/fotoframe)"
        const val CACHE_LIMIT = 600L * 1024 * 1024
    }
}

/**
 * Метрополитен-музей, отдел европейской живописи — около 2,7 тысячи
 * картин. Поиск отдаёт только номера; автор, название и адрес
 * изображения узнаются при первом показе и записываются в индекс.
 */
class MetMuseumSource(context: Context, http: OkHttpClient, dao: () -> PhotoDao) :
    MuseumSource(context, http, dao) {

    override val id = "met"
    override val title = "Метрополитен-музей"
    override val museum = "Метрополитен-музей, Нью-Йорк"

    override suspend fun scan(root: String, onBatch: suspend (List<RemoteItem>) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val found = getJson("$API/search?departmentId=$EUROPEAN_PAINTINGS&hasImages=true&q=painting")
                ?: return@withContext false
            val ids = (found["objectIDs"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() }
                .orEmpty()
            ids.chunked(500).forEach { chunk ->
                onBatch(chunk.map { objectId ->
                    RemoteItem(
                        remoteId = objectId.toString(),
                        uri = "met:$objectId",
                        displayName = museum,
                        albumName = museum,
                        takenAt = 0L
                    )
                })
            }
            ids.isNotEmpty()
        }

    override suspend fun details(photo: Photo): Pair<String, String?>? = withContext(Dispatchers.IO) {
        val o = getJson("$API/objects/${photo.remoteId}") ?: throw IllegalStateException("нет ответа")
        if (o.str("isPublicDomain") != "true") return@withContext null
        // Оригинал бывает на десятки мегапикселей — для телевизора
        // берём его, а если нет — уменьшенную версию.
        val image = o.str("primaryImage") ?: o.str("primaryImageSmall") ?: return@withContext null
        image to caption(o.str("artistDisplayName"), o.str("title"), o.str("objectDate"))
    }

    private companion object {
        const val API = "https://collectionapi.metmuseum.org/public/collection/v1"
        const val EUROPEAN_PAINTINGS = 11
    }
}

/**
 * Кливлендский музей искусств: живопись в открытом доступе (CC0).
 * Список приходит сразу с автором, названием и адресами изображений.
 */
class ClevelandMuseumSource(context: Context, http: OkHttpClient, dao: () -> PhotoDao) :
    MuseumSource(context, http, dao) {

    override val id = "cleveland"
    override val title = "Кливлендский музей искусств"
    override val museum = "Кливлендский музей искусств"

    override suspend fun scan(root: String, onBatch: suspend (List<RemoteItem>) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            var skip = 0
            var any = false
            while (true) {
                val page = getJson(
                    "$API/artworks/?type=Painting&has_image=1&cc0=1&limit=$PAGE&skip=$skip" +
                        "&fields=id,title,creators,creation_date,images"
                ) ?: return@withContext any
                val data = page["data"] as? JsonArray ?: break
                if (data.isEmpty()) break

                val items = data.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val objectId = o.str("id") ?: return@mapNotNull null
                    val images = o["images"] as? JsonObject ?: return@mapNotNull null
                    // Для телевизора — «для печати», она крупнее; нет её — веб.
                    val url = (images["print"] as? JsonObject)?.str("url")
                        ?: (images["web"] as? JsonObject)?.str("url")
                        ?: return@mapNotNull null
                    val artist = ((o["creators"] as? JsonArray)?.firstOrNull() as? JsonObject)
                        ?.str("description")
                        // «Vincent van Gogh (Dutch, 1853–1890)» → «Vincent van Gogh»
                        ?.substringBefore(" (")
                    RemoteItem(
                        remoteId = objectId,
                        uri = url,
                        displayName = caption(artist, o.str("title"), o.str("creation_date")),
                        albumName = museum,
                        takenAt = 0L
                    )
                }
                if (items.isNotEmpty()) {
                    onBatch(items)
                    any = true
                }
                skip += PAGE
                if (data.size < PAGE) break
            }
            any
        }

    override suspend fun details(photo: Photo): Pair<String, String?>? =
        photo.uri.takeIf { it.startsWith("http") }?.let { it to null }

    private companion object {
        const val API = "https://openaccess-api.clevelandart.org/api"
        const val PAGE = 500
    }
}
