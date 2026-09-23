package com.fotoframe.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(photos: List<Photo>): List<Long>

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0")
    suspend fun countEnabled(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0 AND shownInCycle = 0")
    suspend fun countRemainingInCycle(): Int

    // ---------- Выбор кадра ----------

    /**
     * Кандидат по позиции в списке, отсортированном от новых к старым.
     * Смещение вычисляет PhotoPicker — здесь только выборка.
     */
    @Query(
        """
        SELECT * FROM photos
        WHERE enabled = 1 AND hidden = 0 AND shownInCycle = 0
          AND (:cooldownBefore = 0 OR lastShownAt < :cooldownBefore)
        ORDER BY takenAt DESC
        LIMIT 1 OFFSET :offset
        """
    )
    suspend fun photoAtRecencyOffset(offset: Int, cooldownBefore: Long = 0): Photo?

    @Query(
        """
        SELECT COUNT(*) FROM photos
        WHERE enabled = 1 AND hidden = 0 AND shownInCycle = 0
          AND (:cooldownBefore = 0 OR lastShownAt < :cooldownBefore)
        """
    )
    suspend fun countAvailable(cooldownBefore: Long): Int

    /**
     * Пул новинок: попали в индекс недавно, ещё ни разу не показывались,
     * и дата съёмки тоже свежая. Последнее условие важно: при первом
     * обходе архива в индекс за раз попадают все двадцать тысяч снимков,
     * и без него две недели «новинками» была бы вся коллекция.
     */
    @Query(
        """
        SELECT COUNT(*) FROM photos
        WHERE enabled = 1 AND hidden = 0 AND timesShown = 0
          AND firstSeenAt > :since AND takenAt > :since
        """
    )
    suspend fun countFresh(since: Long): Int

    @Query(
        """
        SELECT * FROM photos
        WHERE enabled = 1 AND hidden = 0 AND timesShown = 0
          AND firstSeenAt > :since AND takenAt > :since
        ORDER BY takenAt DESC
        LIMIT 1 OFFSET :offset
        """
    )
    suspend fun freshAt(since: Long, offset: Int): Photo?

    @Query("UPDATE photos SET shownInCycle = 1, timesShown = timesShown + 1, lastShownAt = :now WHERE id = :id")
    suspend fun markShown(id: Long, now: Long)

    /** Круг закончился — начинаем заново. */
    @Query("UPDATE photos SET shownInCycle = 0")
    suspend fun resetCycle()

    // ---------- Дообработка: EXIF ----------

    /**
     * Снимки, у которых ещё не читали EXIF. Неудачные уходят в конец
     * очереди, а после maxTries попыток исключаются.
     */
    @Query(
        """
        SELECT * FROM photos
        WHERE enabled = 1 AND hidden = 0 AND metaDone = 0 AND metaTries < :maxTries
        ORDER BY metaTries, id
        LIMIT :limit
        """
    )
    suspend fun photosNeedingMeta(limit: Int, maxTries: Int): List<Photo>

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0 AND metaDone = 0 AND metaTries < :maxTries")
    suspend fun countNeedingMeta(maxTries: Int): Int

    @Query("UPDATE photos SET metaDone = 1 WHERE id = :id")
    suspend fun markMetaDone(id: Long)

    @Query("UPDATE photos SET metaTries = metaTries + 1 WHERE id = :id")
    suspend fun bumpMetaTries(id: Long)

    /** Дата съёмки, уточнённая из EXIF самим файлом, — настоящая. */
    @Query("UPDATE photos SET takenAt = :takenAt, takenAtExact = 1 WHERE id = :id")
    suspend fun setTakenAt(id: Long, takenAt: Long)

    /**
     * Дата от источника для уже известной записи, у которой настоящей даты
     * ещё нет. Нужно один раз после обновления: старые записи с устройства
     * и Яндекса получают флаг, не переиндексируясь заново.
     */
    @Query(
        """
        UPDATE photos SET takenAt = :takenAt, takenAtExact = 1
        WHERE sourceId = :sourceId AND remoteId = :remoteId AND takenAtExact = 0
        """
    )
    suspend fun setTakenAtIfNotExact(sourceId: String, remoteId: String, takenAt: Long)

    @Query("SELECT remoteId FROM photos WHERE sourceId = :sourceId AND takenAtExact = 0")
    suspend fun remoteIdsWithoutExactDate(sourceId: String): List<String>

    /**
     * Размеры кадра из EXIF. Сетевая папка их при обходе не сообщает, а без
     * них не работают ни фильтр по разрешению, ни выбор стороны кадрирования.
     */
    @Query("UPDATE photos SET width = :width, height = :height WHERE id = :id")
    suspend fun setDimensions(id: Long, width: Int, height: Int)

    /** Координаты съёмки, прочитанные из EXIF. */
    @Query("UPDATE photos SET latitude = :lat, longitude = :lon WHERE id = :id")
    suspend fun setCoords(id: Long, lat: Double, lon: Double)

    // ---------- Дообработка: места ----------

    /**
     * Снимки с координатами, но пока без разрешённого названия места.
     * Жёсткого потолка попыток здесь нет — сбой геокодера почти всегда
     * временный, — но неудачные уходят в конец очереди.
     */
    @Query(
        """
        SELECT * FROM photos
        WHERE enabled = 1 AND hidden = 0 AND placeName IS NULL
          AND latitude IS NOT NULL AND longitude IS NOT NULL
        ORDER BY placeTries, id
        LIMIT :limit
        """
    )
    suspend fun photosNeedingPlace(limit: Int): List<Photo>

    @Query("UPDATE photos SET placeName = :place WHERE id = :id")
    suspend fun setPlace(id: Long, place: String)

    @Query("UPDATE photos SET placeTries = placeTries + 1 WHERE id = :id")
    suspend fun bumpPlaceTries(id: Long)

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0 AND placeName IS NOT NULL AND placeName != ''")
    suspend fun countWithPlace(): Int

    // ---------- Дообработка: лица ----------

    @Query(
        """
        SELECT * FROM photos
        WHERE enabled = 1 AND hidden = 0 AND faceCount = -1 AND faceTries < :maxTries
        ORDER BY faceTries, id
        LIMIT :limit
        """
    )
    suspend fun photosNeedingFocus(limit: Int, maxTries: Int): List<Photo>

    @Query("UPDATE photos SET focusX = :x, focusY = :y, faceCount = :faces WHERE id = :id")
    suspend fun setFocus(id: Long, x: Float, y: Float, faces: Int)

    @Query("UPDATE photos SET faceTries = faceTries + 1 WHERE id = :id")
    suspend fun bumpFaceTries(id: Long)

    // ---------- Разовые сбросы ----------

    @Query("UPDATE photos SET rotation = :degrees WHERE id = :id")
    suspend fun setRotation(id: Long, degrees: Int)

    // ---------- Серии похожих кадров ----------

    @Query("UPDATE photos SET phash = :hash WHERE id = :id")
    suspend fun setHash(id: Long, hash: Long)

    /**
     * Соседи по времени из той же папки с посчитанным хэшем, ещё не
     * показанные в этом цикле. Похожесть решается уже в Kotlin: расстояние
     * между хэшами в SQL не посчитать.
     */
    @Query(
        """
        SELECT * FROM photos
        WHERE albumName = :album AND id != :id
          AND takenAt BETWEEN :from AND :to
          AND phash IS NOT NULL
          AND enabled = 1 AND hidden = 0 AND shownInCycle = 0
        LIMIT 40
        """
    )
    suspend fun neighboursInTime(id: Long, album: String, from: Long, to: Long): List<Photo>

    /** Снимки серии пропускаются в этом цикле — как показанные, но без счётчика. */
    @Query("UPDATE photos SET shownInCycle = 1 WHERE id IN (:ids)")
    suspend fun skipInCycle(ids: List<Long>)

    // ---------- Скрытые пользователем ----------

    @Query("UPDATE photos SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun photoById(id: Long): Photo?

    /** Файла больше нет в хранилище — убрать запись вместе с историей показов. */
    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE photos SET hidden = 0 WHERE hidden = 1")
    suspend fun unhideAll()

    @Query("SELECT COUNT(*) FROM photos WHERE hidden = 1")
    suspend fun countHidden(): Int

    /** Последние скрытые — первыми. */
    @Query("SELECT * FROM photos WHERE hidden = 1 ORDER BY id DESC LIMIT :limit")
    suspend fun hiddenPhotos(limit: Int): List<Photo>

    /**
     * Сбросить только лица. Нужно после исправления детектора: раньше он
     * не учитывал ориентацию EXIF, и у повёрнутых снимков либо не находил
     * лиц вовсе, либо записывал их координаты в неповёрнутой системе.
     */
    @Query("UPDATE photos SET faceCount = -1, focusX = -1, focusY = -1, faceTries = 0")
    suspend fun resetFaces()

    /** Сбросить лица и места — после исправления ошибок детектора. */
    @Query(
        """
        UPDATE photos SET faceCount = -1, focusX = -1, focusY = -1, placeName = NULL,
            faceTries = 0, placeTries = 0
        """
    )
    suspend fun resetEnrichment()

    /** Перечитать EXIF по всей коллекции — после исправлений его разбора. */
    @Query("UPDATE photos SET metaDone = 0, placeName = NULL, metaTries = 0, placeTries = 0")
    suspend fun resetMetadata()

    /** То же, но только для снимков, у которых координат так и нет. */
    @Query("UPDATE photos SET metaDone = 0, metaTries = 0 WHERE latitude IS NULL OR longitude IS NULL")
    suspend fun resetMetadataWithoutCoords()

    /**
     * Снять пустое «места нет», записанное прежними версиями: системный
     * геокодер без сети отвечал пустым списком, и это считалось ответом.
     * Такие снимки уходят на перепроверку через OSM.
     */
    /**
     * Сбросить подписи мест, чтобы они пересчитались. Нужно после правки
     * отбора названий: у снимков из Таиланда и Китая в базе осели
     * подписи на местной письменности.
     */
    @Query("UPDATE photos SET placeName = NULL, placeTries = 0 WHERE placeName IS NOT NULL")
    suspend fun resetPlaces()

    @Query("UPDATE photos SET placeName = NULL, placeTries = 0 WHERE placeName = ''")
    suspend fun resetEmptyPlaces(): Int

    /**
     * Перечитать EXIF у снимков, для которых неизвестны размеры кадра.
     * Без них не отличить вертикальный снимок от горизонтального.
     */
    @Query("UPDATE photos SET metaDone = 0, metaTries = 0 WHERE width = 0 OR height = 0")
    suspend fun resetMetadataWithoutDimensions()

    /** Перечитать EXIF у снимков без настоящей даты съёмки. */
    @Query("UPDATE photos SET metaDone = 0, metaTries = 0 WHERE takenAtExact = 0")
    suspend fun resetMetadataWithoutExactDate()

    /**
     * Дать ещё несколько попыток всему, что не удалось: вызывается при
     * ручном обновлении списка — например, когда хранилище снова в сети.
     */
    @Query(
        """
        UPDATE photos SET metaTries = 0, faceTries = 0, placeTries = 0
        WHERE metaDone = 0 OR faceCount = -1 OR placeName IS NULL
        """
    )
    suspend fun resetTries()

    // ---------- Фильтр содержимого ----------

    /** Всё, что нужно фильтру, без тяжёлых полей. Решение принимает ContentFilter. */
    @Query("SELECT id, sourceId, displayName, albumName, width, height, sizeBytes FROM photos")
    suspend fun filterRows(): List<FilterRow>

    @Query("UPDATE photos SET enabled = :enabled WHERE id IN (:ids)")
    suspend fun setEnabledByIds(ids: List<Long>, enabled: Boolean)

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 0")
    suspend fun countFiltered(): Int

    /** Имена файлов без настоящей даты — для разбора даты из имени. */
    @Query("SELECT id, displayName FROM photos WHERE takenAtExact = 0 LIMIT :limit OFFSET :offset")
    suspend fun namesWithoutExactDate(limit: Int, offset: Int): List<NameRow>

    /**
     * Вертикальный снимок в пару к показываемому. Ориентация известна
     * только когда размеры прочитаны из EXIF, поэтому width > 0 —
     * обязательное условие, а не перестраховка.
     *
     * Сначала ищется снимок из той же папки: пара из одной поездки
     * смотрится осмысленнее случайного соседства.
     */
    @Query(
        """
        SELECT * FROM photos
        WHERE enabled = 1 AND hidden = 0 AND shownInCycle = 0 AND id != :excludeId
          AND width > 0 AND height > width
          AND (:album IS NULL OR albumName = :album)
          AND (:cooldownBefore = 0 OR lastShownAt < :cooldownBefore)
          AND (:people = 0 OR faceCount > 0)
          AND (:noPeople = 0 OR faceCount = 0)
        ORDER BY RANDOM()
        LIMIT 1
        """
    )
    suspend fun randomPortrait(
        excludeId: Long,
        album: String?,
        cooldownBefore: Long,
        people: Int = 0,
        noPeople: Int = 0
    ): Photo?

    /**
     * Партнёр той же природы, когда непоказанных не осталось: самый
     * давно показанный из снимков с людьми или без — смотря к какому
     * относится первый кадр.
     */
    @Query(
        """
        SELECT * FROM photos
        WHERE enabled = 1 AND hidden = 0 AND id != :excludeId
          AND width > 0 AND height > width
          AND (:people = 0 OR faceCount > 0)
          AND (:noPeople = 0 OR faceCount = 0)
        ORDER BY lastShownAt ASC
        LIMIT 1
        """
    )
    suspend fun leastRecentPortraitLike(excludeId: Long, people: Int, noPeople: Int): Photo?

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0 AND width > 0 AND height > width")
    suspend fun countPortraits(): Int

    /**
     * Партнёр в пару, когда непоказанных вертикальных в цикле не осталось:
     * самый давно показанный. На маленькой коллекции все вертикальные
     * успевают побывать на экране за час, и пары иначе кончались.
     */
    @Query(
        """
        SELECT * FROM photos
        WHERE enabled = 1 AND hidden = 0 AND id != :excludeId
          AND width > 0 AND height > width
        ORDER BY lastShownAt ASC
        LIMIT 1
        """
    )
    suspend fun leastRecentPortrait(excludeId: Long): Photo?

    /**
     * Вернуть в очередь записи, помеченные обработанными без размеров:
     * подготовка кадра раньше читала у HEIC только начало файла и
     * ставила отметку с пустым результатом.
     */
    @Query("UPDATE photos SET metaDone = 0, metaTries = 0 WHERE metaDone = 1 AND (width = 0 OR height = 0)")
    suspend fun resetMetaWithoutDims()

    // ---------- Диагностика ----------

    @Query("SELECT * FROM photos WHERE id = :id")
    suspend fun byId(id: Long): Photo?

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0 AND sourceId = :sourceId")
    suspend fun countBySource(sourceId: String): Int

    /** Всего записей от источника, включая отсеянные. */
    @Query("SELECT COUNT(*) FROM photos WHERE sourceId = :sourceId")
    suspend fun countAllBySource(sourceId: String): Int

    /** Убрать из индекса всё от источника — вместе с историей показов. */
    @Query("DELETE FROM photos WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0 AND takenAtExact = 1")
    suspend fun countExactDate(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0 AND latitude IS NOT NULL AND longitude IS NOT NULL")
    suspend fun countWithCoords(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0 AND metaDone = 1")
    suspend fun countMetaDone(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0 AND metaDone = 0 AND metaTries >= :maxTries")
    suspend fun countMetaStuck(maxTries: Int): Int

    @Query(
        """
        SELECT COUNT(*) FROM photos
        WHERE enabled = 1 AND hidden = 0 AND placeName IS NULL
          AND latitude IS NOT NULL AND longitude IS NOT NULL
        """
    )
    suspend fun countPlacePending(): Int

    @Query("SELECT COUNT(*) FROM photos WHERE enabled = 1 AND hidden = 0 AND placeName = ''")
    suspend fun countPlaceEmpty(): Int

    // ---------- Индексация ----------

    @Query("SELECT remoteId FROM photos WHERE sourceId = :sourceId")
    suspend fun knownRemoteIds(sourceId: String): List<String>

    /** Ссылки на превью известных записей — чтобы обновить их при смене размера превью. */
    @Query("SELECT remoteId, thumbnailUrl FROM photos WHERE sourceId = :sourceId")
    suspend fun knownThumbnails(sourceId: String): List<ThumbRow>

    @Query("UPDATE photos SET thumbnailUrl = :url WHERE sourceId = :sourceId AND remoteId = :remoteId")
    suspend fun setThumbnail(sourceId: String, remoteId: String, url: String?)

    @Query("DELETE FROM photos WHERE sourceId = :sourceId AND remoteId IN (:remoteIds)")
    suspend fun deleteByRemoteIds(sourceId: String, remoteIds: List<String>)
}
