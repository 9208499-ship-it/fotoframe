package com.fotoframe.source.yandex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * REST API Яндекс.Диска. Базовый адрес — https://cloud-api.yandex.net/v1/
 * Токен передаётся заголовком вида "OAuth <token>".
 *
 * Документация: https://yandex.ru/dev/disk/api/
 */
interface YandexApi {

    /**
     * Содержимое папки. Путь корня Диска — "disk:/".
     * Поле preview_size даёт готовую уменьшенную копию, благодаря чему
     * на экран не тянется исходник на несколько мегабайт. Размер задаётся
     * числом ([PREVIEW_SIZE]): именованные S…XXXL заканчиваются на 1280 px,
     * и на панели 4K это было заметно.
     */
    @GET("disk/resources")
    suspend fun listResources(
        @Header("Authorization") auth: String,
        @Query("path") path: String,
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("sort") sort: String = "name",
        @Query("preview_size") previewSize: String = PREVIEW_SIZE,
        @Query("preview_crop") previewCrop: Boolean = false,
        @Query("fields") fields: String = LIST_FIELDS
    ): ResourceResponse

    /**
     * Плоский список файлов по всему Диску с фильтром по типу.
     * Быстрее рекурсивного обхода, когда нужны просто все фотографии.
     */
    @GET("disk/resources/files")
    suspend fun listFiles(
        @Header("Authorization") auth: String,
        @Query("limit") limit: Int = 200,
        @Query("offset") offset: Int = 0,
        @Query("media_type") mediaType: String = "image",
        @Query("preview_size") previewSize: String = PREVIEW_SIZE,
        @Query("preview_crop") previewCrop: Boolean = false,
        @Query("fields") fields: String = FILES_FIELDS
    ): FilesResponse

    /** Временная прямая ссылка на файл. Живёт недолго, кэшировать не нужно. */
    @GET("disk/resources/download")
    suspend fun downloadLink(
        @Header("Authorization") auth: String,
        @Query("path") path: String
    ): DownloadLink

    /** Проверка токена и заодно сведения о свободном месте. */
    /**
     * В Корзину Диска, откуда файл восстанавливается 30 дней.
     * permanently=false — значение по умолчанию, указано явно.
     */
    @DELETE("disk/resources")
    suspend fun deleteResource(
        @Header("Authorization") auth: String,
        @Query("path") path: String,
        @Query("permanently") permanently: Boolean = false
    ): retrofit2.Response<okhttp3.ResponseBody>

    @GET("disk")
    suspend fun diskInfo(
        @Header("Authorization") auth: String
    ): DiskInfo

    companion object {
        const val BASE_URL = "https://cloud-api.yandex.net/v1/"

        /**
         * Ширина превью в пикселях. API принимает и именованные размеры,
         * и точные: «<ширина>x», «x<высота>», «<ширина>x<высота>».
         * 1920 — компромисс между качеством и временем загрузки: для 4K
         * можно поставить "3840x", но превью станут вчетверо тяжелее.
         */
        const val PREVIEW_SIZE = "1920x"

        /**
         * Поля элемента. В параметре fields каждый элемент списка — полный
         * путь в JSON, поэтому префикс надо повторять перед каждым полем:
         * «_embedded.items.name,path» означает «имя у элементов и path на
         * верхнем уровне», а не «name и path у элементов». С такой записью
         * приходили только имена, тип не приходил, и список папок был пуст.
         */
        private val ITEM_FIELDS = listOf(
            "name", "path", "type", "mime_type", "size", "created", "modified",
            "preview", "exif.date_time", "media_type"
        )

        val LIST_FIELDS: String =
            ITEM_FIELDS.joinToString(",") { "_embedded.items.$it" } +
                ",_embedded.total,_embedded.offset"

        val FILES_FIELDS: String = ITEM_FIELDS.joinToString(",") { "items.$it" }

        fun auth(token: String) = "OAuth $token"
    }
}

@Serializable
data class DiskInfo(
    @SerialName("total_space") val totalSpace: Long = 0,
    @SerialName("used_space") val usedSpace: Long = 0
)

@Serializable
data class ResourceResponse(
    @SerialName("_embedded") val embedded: Embedded? = null
)

@Serializable
data class Embedded(
    val items: List<Resource> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0
)

@Serializable
data class FilesResponse(
    val items: List<Resource> = emptyList()
)

@Serializable
data class Resource(
    val name: String = "",
    val path: String = "",
    /** "file" или "dir" */
    val type: String = "",
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val size: Long = 0,
    val created: String? = null,
    val modified: String? = null,
    /** Готовая ссылка на уменьшенную копию. */
    val preview: String? = null,
    val exif: Exif? = null
) {
    val isDir: Boolean get() = type == "dir"
    val isImage: Boolean get() = mediaType == "image" || mimeType?.startsWith("image/") == true
}

@Serializable
data class Exif(
    @SerialName("date_time") val dateTime: String? = null
)

@Serializable
data class DownloadLink(
    val href: String = "",
    val method: String = "GET"
)
