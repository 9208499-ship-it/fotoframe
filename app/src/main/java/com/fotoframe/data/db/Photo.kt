package com.fotoframe.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Одна карточка медиафайла в индексе.
 *
 * Индекс намеренно хранит только метаданные — сами файлы остаются
 * в источнике и подгружаются в момент показа. Это позволяет держать
 * десятки тысяч записей и выбирать среди них одним SQL-запросом.
 */
@Entity(
    tableName = "photos",
    indices = [
        Index(value = ["sourceId", "remoteId"], unique = true),
        Index(value = ["shownInCycle", "takenAt"]),
        Index(value = ["firstSeenAt"])
    ]
)
data class Photo(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Идентификатор источника: "local", "yandex" и так далее. */
    val sourceId: String,

    /** Идентификатор внутри источника: путь на Диске, MediaStore ID. */
    val remoteId: String,

    /** Что открывать при показе. Для облака — путь, ссылка берётся позже. */
    val uri: String,

    val displayName: String,

    /** Папка или альбом — по нему работает фильтр в настройках. */
    val albumName: String? = null,

    /**
     * Дата съёмки. Берётся из EXIF, при отсутствии — из даты файла.
     * Это то поле, по которому работает приоритет новых фотографий.
     */
    val takenAt: Long,

    /**
     * Известна ли настоящая дата съёмки (EXIF, DATE_TAKEN, exif Яндекса),
     * или в takenAt лежит дата файла. Подпись даты на экране показывается
     * только для настоящей — дата копирования на NAS никому не нужна.
     */
    val takenAtExact: Boolean = false,

    /** Когда карточка впервые попала в индекс. Для показа новинок. */
    val firstSeenAt: Long,

    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0,

    /** Ссылка на уменьшенную копию, если источник её отдаёт. */
    val thumbnailUrl: String? = null,

    /** Показана ли карточка в текущем круге показа. */
    val shownInCycle: Boolean = false,

    /** Сколько раз показана за всё время. */
    val timesShown: Int = 0,

    val lastShownAt: Long = 0,

    /**
     * Координаты точки фокуса в долях от размеров кадра (0..1), найденные
     * детектором лиц. -1 означает «ещё не проверяли», отсутствие лиц
     * сохраняется как центр 0.5/0.5 с faceCount = 0, чтобы не запускать
     * детектор повторно.
     */
    val focusX: Float = -1f,
    val focusY: Float = -1f,
    val faceCount: Int = -1,

    /**
     * Перцептивный хэш (dHash, 64 бита) — чтобы узнавать серии почти
     * одинаковых кадров. null — ещё не считали. Считается вместе с
     * поиском лиц, на той же уменьшенной копии.
     */
    val phash: Long? = null,

    /**
     * Ручной поворот с пульта, градусы по часовой: 0, 90, 180, 270.
     * Поверх ориентации из EXIF. Нужен снимкам, испорченным старыми
     * программами: они поворачивали пиксели, но оставляли в EXIF прежнюю
     * метку, и честное применение EXIF показывает их вверх ногами.
     */
    val rotation: Int = 0,

    /** Координаты съёмки из EXIF, если есть. Для подписи города. */
    val latitude: Double? = null,
    val longitude: Double? = null,

    /** Название места, разрешённое из координат один раз и сохранённое. */
    val placeName: String? = null,

    /**
     * Прочитан ли EXIF самого файла: дата съёмки и координаты. Отдельно от
     * faceCount, потому что это разные по цене операции — заголовок файла
     * читается за доли секунды, а поиск лиц требует всей картинки.
     */
    val metaDone: Boolean = false,

    /**
     * Счётчики неудачных попыток по каждому проходу дообработки. Снимок,
     * который не удалось прочитать, уходит в конец очереди, а после
     * нескольких неудач исключается — иначе двести подряд недоступных
     * файлов в начале таблицы останавливали весь проход навсегда.
     */
    val metaTries: Int = 0,
    val faceTries: Int = 0,
    val placeTries: Int = 0,

    /** Снимается фильтром содержимого в настройках. */
    val enabled: Boolean = true,

    /**
     * Скрыт пользователем с пульта. Отдельно от [enabled]: фильтр
     * содержимого пересчитывает enabled по всей коллекции при каждом
     * обновлении и вернул бы скрытое обратно. Файл в хранилище не трогается,
     * вернуть можно из настроек.
     */
    val hidden: Boolean = false
) {
    val isPortrait: Boolean get() = height > width
}

/** Идентификатор и имя файла — для разбора даты из имени. */
data class NameRow(
    val id: Long,
    val displayName: String
)

/** Идентификатор в источнике и сохранённая ссылка на превью. */
data class ThumbRow(
    val remoteId: String,
    val thumbnailUrl: String?
)

/** Срез полей, по которым работает фильтр содержимого. */
data class FilterRow(
    val id: Long,
    val sourceId: String,
    val displayName: String,
    val albumName: String?,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)
