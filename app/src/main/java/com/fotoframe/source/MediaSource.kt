package com.fotoframe.source

import com.fotoframe.data.db.Photo

/**
 * Описание одного файла, как его вернул источник.
 * В индекс попадает после преобразования в [Photo].
 */
data class RemoteItem(
    val remoteId: String,
    val uri: String,
    val displayName: String,
    val albumName: String?,
    val takenAt: Long,
    /** true — takenAt это дата съёмки, false — дата файла (уточнит EXIF). */
    val takenAtExact: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,
    val thumbnailUrl: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class Folder(
    val path: String,
    val name: String
)

/**
 * Источник фотографий. Добавить новое облако — значит реализовать
 * этот интерфейс и зарегистрировать его в SourceRegistry. Всё остальное —
 * индексация, выбор кадров, показ — уже написано и не меняется.
 */
interface MediaSource {

    /** Короткий идентификатор, попадает в Photo.sourceId. */
    val id: String

    /** Название для экрана настроек. */
    val title: String

    /** Готов ли источник к работе: выданы разрешения, есть токен. */
    suspend fun isReady(): Boolean

    /** Список папок для выбора в настройках. */
    suspend fun listFolders(parent: String?): List<Folder>

    /**
     * Полный обход. Реализация должна отдавать элементы порциями через
     * [onBatch], а не собирать всё в один список: коллекции бывают
     * на десятки тысяч файлов.
     *
     * @return true, если обход прошёл целиком. false — часть папок
     *   прочитать не удалось; по такому обходу индекс ничего не удаляет,
     *   иначе временно недоступная папка выглядела бы как исчезнувшие файлы.
     */
    suspend fun scan(root: String, onBatch: suspend (List<RemoteItem>) -> Unit): Boolean

    /**
     * Что передать загрузчику изображений. Для локальных файлов это сам uri,
     * для облака — временная ссылка, которую нужно запросить перед показом.
     */
    suspend fun resolveDisplayUrl(photo: Photo): String

    /**
     * Убрать файл из хранилища. Реализации по возможности не удаляют
     * безвозвратно: сетевая папка переносит файл в свою корзину, Яндекс
     * кладёт в Корзину Диска, откуда он живёт 30 дней.
     *
     * Источник, который удалять не умеет или не имеет прав, возвращает
     * [DeleteResult.Unsupported] с человеческой причиной — она уйдёт на
     * экран как есть.
     */
    suspend fun delete(photo: Photo): DeleteResult = DeleteResult.Unsupported("Источник не поддерживает удаление")
}

/** Чем закончилась попытка убрать файл. */
sealed interface DeleteResult {

    /** Файл убран. [note] — куда именно, для сообщения пользователю. */
    data class Done(val note: String) : DeleteResult

    /** Не вышло: нет прав, нет сети, отказал сервер. */
    data class Failed(val reason: String) : DeleteResult

    /** Источник этого не умеет — например, нужен другой токен. */
    data class Unsupported(val reason: String) : DeleteResult

    /**
     * Нужно согласие пользователя через системный диалог. Android с
     * 11-й версии не даёт приложению стереть чужой файл молча.
     */
    data class NeedsConfirmation(val request: android.app.PendingIntent) : DeleteResult
}
