package com.fotoframe.engine

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.fotoframe.App
import com.fotoframe.data.db.Photo
import com.fotoframe.data.db.PhotoDao
import com.fotoframe.source.SourceRegistry
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Досчитывает по снимкам то, что нужно для показа, но не приходит от
 * источника: дату съёмки, координаты, название места и положение лиц.
 *
 * Работа разбита на три прохода, потому что они на порядки различаются по
 * цене, а раньше шли одним куском и упирались в самый дорогой:
 *
 * 1. [readMetadata] — дата и координаты из EXIF. У сетевой папки читается
 *    только начало файла, у MediaStore — оригинал с геоданными.
 * 2. [resolvePlaces] — названия городов по координатам.
 * 3. [detectFaces] — поиск лиц. Требует всей картинки, поэтому самый
 *    медленный и идёт последним, уже не задерживая подписи.
 *
 * Результат каждого прохода пишется в индекс, так что для снимка это
 * делается один раз за всё время.
 *
 * Про неудачи. Снимок, который не удалось прочитать, получает +1 к счётчику
 * попыток и уходит в конец очереди; после [MAX_TRIES] неудач он исключается
 * до ручного обновления списка. Раньше сбойные снимки просто пропускались,
 * а очередь всегда начиналась с одних и тех же записей — и двухсот подряд
 * недоступных файлов хватало, чтобы проход по всей коллекции встал навсегда.
 */
class PhotoEnricher(
    private val context: Context,
    private val dao: PhotoDao,
    private val sources: SourceRegistry
) {
    /** Сколько снимков без координат уже расписано в лог подробно. */
    private var noGeoLogged = 0
    private val geocoder: Geocoder? =
        if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null

    /**
     * Полная дообработка коллекции: даты и координаты, затем города, затем
     * лица. Разбор и подписи чередуются, чтобы города появлялись сразу, а
     * не после разбора всего архива.
     *
     * Одновременно работает только один проход. Экран настроек и фоновый
     * воркер запускают дообработку независимо друг от друга, и без этого
     * они разбирали одни и те же снимки параллельно.
     *
     * @param onStage вызывается после каждого этапа для показа прогресса.
     */
    suspend fun enrichAll(onStage: suspend () -> Unit = {}) {
        if (!gate.tryLock()) {
            Log.i(TAG, "Дообработка уже идёт — пропускаем повторный запуск")
            return
        }

        try {
            // Проход продолжается, пока очередь отдаёт записи, а не пока
            // есть успешные: раньше пачка из двухсот недоступных файлов
            // (например, удалённых с устройства, но оставшихся в индексе)
            // завершала весь проход, хотя дальше лежали тысячи обычных
            // снимков. Ограничение — подряд идущие пустые пачки: когда
            // не читается вообще ничего, хранилище, скорее всего, лежит.
            var barren = 0
            while (true) {
                val p = readMetadata(200)
                if (p.touched == 0) break
                if (p.done > 0) {
                    barren = 0
                    resolvePlaces(20)
                } else if (++barren >= BARREN_LIMIT) {
                    Log.w(TAG, "EXIF: $BARREN_LIMIT пачек подряд без результата — пауза до следующего раза")
                    break
                }
            }

            // Места: сбой геокодера почти всегда общий (нет сети), поэтому
            // здесь достаточно первого пустого результата.
            while (resolvePlaces(40) > 0) { /* дальше */ }
            onStage()

            barren = 0
            while (true) {
                val p = detectFaces(60)
                if (p.touched == 0) break
                if (p.done > 0) barren = 0
                else if (++barren >= BARREN_LIMIT) {
                    Log.w(TAG, "Лица: $BARREN_LIMIT пачек подряд без результата — пауза до следующего раза")
                    break
                }
            }
            Log.i(TAG, "Дообработка завершена")
        } finally {
            gate.unlock()
        }
    }

    /**
     * Итог одной пачки: сколько записей затронуто (прочитано или получило
     * +1 к попыткам) и сколько из них прочитано успешно.
     */
    data class Pass(val touched: Int, val done: Int)

    /** Пока рамка на экране, дорогие сетевые операции идут вполсилы. */
    private val uiVisible: Boolean
        get() = (context.applicationContext as? App)?.uiVisible == true

    /**
     * Разобрать один снимок немедленно — тот, что сейчас выходит на экран.
     *
     * Сплошной проход по коллекции идёт по порядку записей, а кадры
     * выбираются случайно, поэтому на архиве в десятки тысяч файлов шанс
     * попасть на уже разобранный снимок ничтожен, и подписи не появлялись
     * месяцами. Здесь тот же разбор делается для конкретного кадра: одно
     * чтение заголовка, если нужно — один запрос к геокодеру, и поиск лиц.
     *
     * Лица ищутся по той же ссылке, что идёт на экран ([displayUrl]):
     * картинку для показа всё равно скачивают, а загрузчик отдаёт её уже
     * повёрнутой по EXIF и складывает в кэш — показ потом берёт оттуда.
     * Без этого на большой коллекции наезд на лицо не работал никогда:
     * фоновый проход добирался до случайно выбранного кадра через месяцы.
     *
     * Возвращает обновлённую карточку — с датой, местом и лицами.
     */
    suspend fun enrichNow(photo: Photo, displayUrl: String? = null): Photo = withContext(Dispatchers.IO) {
        var result = photo

        if (!result.metaDone && result.metaTries < MAX_TRIES) {
            // Файл с NAS к этому моменту уже скачан в кэш для показа —
            // EXIF читается из него целиком, а не из 128 КБ по сети. Для
            // HEIC это единственный способ: его метаданные по началу
            // файла не разбираются, и раньше такой кадр помечался
            // «обработан» с пустым результатом.
            val cached = displayUrl
                ?.takeIf { it.startsWith("file://") }
                ?.let { File(Uri.parse(it).path ?: return@let null) }
                ?.takeIf { it.isFile && it.length() > 0 }
            result = readOne(result, cached)
        }

        val lat = result.latitude
        val lon = result.longitude
        if (lat != null && lon != null && result.placeName.isNullOrEmpty()) {
            val name = lookup(lat, lon)
            if (name != null) {
                dao.setPlace(result.id, name)
                result = result.copy(placeName = name)
            } else {
                dao.bumpPlaceTries(result.id)
            }
        }

        if (displayUrl != null && result.faceCount == -1 && result.faceTries < MAX_TRIES) {
            result = detectOne(result, displayUrl)
        }

        result
    }

    /**
     * Лица на одном снимке через загрузчик картинок. Coil сам применяет
     * ориентацию EXIF, поэтому координаты сразу в той системе, в которой
     * кадр рисуется. Картинку не освобождаем — она принадлежит кэшу.
     */
    private suspend fun detectOne(photo: Photo, url: String): Photo {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(DETECT_SIZE)
            .allowHardware(false)
            .build()

        val loaded = tryOrNull { context.imageLoader.execute(request) } as? SuccessResult
            ?: return photo
        val bitmap = (loaded.drawable as? BitmapDrawable)?.bitmap ?: return photo
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return photo

        val focus = try {
            FaceFocusDetector.detect(InputImage.fromBitmap(bitmap, 0), bitmap.width, bitmap.height)
        } catch (e: Throwable) {
            e.rethrowIfCancelled()
            Log.w(TAG, "Детектор лиц не сработал на ${photo.displayName}: ${e.message}")
            dao.bumpFaceTries(photo.id)
            return photo.copy(faceTries = photo.faceTries + 1)
        }

        val x = focus?.centerX ?: 0.5f
        val y = focus?.centerY ?: 0.5f
        val n = focus?.faceCount ?: 0
        dao.setFocus(photo.id, x, y, n)
        return photo.copy(focusX = x, focusY = y, faceCount = n)
    }

    /**
     * Заголовок одного файла: дата и координаты. Полное перечитывание HEIC
     * здесь не делается — по сети это десятки мегабайт, а кадр ждать не
     * может; такие снимки дочитает фоновый проход.
     */
    private suspend fun readOne(photo: Photo, cached: File? = null): Photo {
        var result = photo

        // Имя файла бесплатно и почти всегда содержит момент съёмки.
        if (!result.takenAtExact) {
            FileNameDate.parse(result.displayName)?.let {
                dao.setTakenAt(result.id, it)
                result = result.copy(takenAt = it, takenAtExact = true)
            }
        }

        val header = tryOrNull {
            if (cached != null) Header(ExifInterface(cached), null, cached) else openHeader(photo, deep = false)
        }

        if (header == null) {
            // Хранилище целиком недоступно — это не вина снимка, попытку
            // не засчитываем: иначе три запуска при выключенном NAS
            // исключали бы снимок до ручного обновления.
            if (!sourceDown(photo)) dao.bumpMetaTries(photo.id)
            return result
        }
        val exif = header.exif

        // EXIF главнее имени файла: если дата есть в заголовке, берём её.
        runCatching { exifDate(exif) }.getOrNull()?.let {
            dao.setTakenAt(result.id, it)
            result = result.copy(takenAt = it, takenAtExact = true)
        }

        val coords = runCatching { exif.latLong }.getOrNull()
        if (coords != null && (coords[0] != 0.0 || coords[1] != 0.0)) {
            dao.setCoords(result.id, coords[0], coords[1])
            result = result.copy(latitude = coords[0], longitude = coords[1])
        }

        (applyDimensions(exif, result) ?: runCatching { probeDimensions(header, result) }.getOrNull())
            ?.let { (w, h) ->
                dao.setDimensions(result.id, w, h)
                result = result.copy(width = w, height = h)
            }

        dao.markMetaDone(result.id)
        return result.copy(metaDone = true)
    }

    // ---------- 1. Дата съёмки и координаты ----------

    /** Одна пачка разбора EXIF. */
    suspend fun readMetadata(limit: Int = 200): Pass = withContext(Dispatchers.IO) {
        val batch = dao.photosNeedingMeta(limit, MAX_TRIES)
        var touched = 0
        var done = 0
        var withDate = 0
        var withGeo = 0
        var deepRescued = 0

        for (photo in batch) {
            // Лежащее хранилище пропускаем целиком: каждая попытка
            // подключения стоит до двадцати секунд.
            if (sourceDown(photo)) continue

            val quickHeader = tryOrNull { openHeader(photo, deep = false) }

            if (quickHeader == null) {
                if (!sourceDown(photo)) {
                    dao.bumpMetaTries(photo.id)
                    touched++
                }
                continue
            }

            var header = quickHeader
            var exif = quickHeader.exif
            var coords = runCatching { exif.latLong }.getOrNull()

            // Перечитывать снимок целиком имеет смысл только для HEIC:
            // там метаданные разбираются лишь по всему файлу. У JPEG блок
            // EXIF стоит в начале, и если координат в нём нет, значит их
            // нет и в файле.
            if (coords == null && photo.sourceId == "smb" && isHeif(photo.displayName)) {
                val full = tryOrNull { openHeader(photo, deep = true) }
                if (full != null) {
                    val deepCoords = runCatching { full.exif.latLong }.getOrNull()
                    if (deepCoords != null) {
                        deepRescued++
                        coords = deepCoords
                    }
                    header = full
                    exif = full.exif
                }
            }

            val meta = exif

            // Дата: сперва из заголовка, при её отсутствии — из имени файла.
            val parsedDate = runCatching { exifDate(meta) }.getOrNull()
                ?: FileNameDate.parse(photo.displayName)
            if (parsedDate != null) {
                dao.setTakenAt(photo.id, parsedDate)
                withDate++
            }

            (applyDimensions(meta, photo) ?: runCatching { probeDimensions(header, photo) }.getOrNull())
                ?.let { (w, h) -> dao.setDimensions(photo.id, w, h) }

            if (coords != null && (coords[0] != 0.0 || coords[1] != 0.0)) {
                dao.setCoords(photo.id, coords[0], coords[1])
                withGeo++
            } else if (noGeoLogged < LOG_SAMPLES) {
                noGeoLogged++
                val lat = meta.getAttribute(ExifInterface.TAG_GPS_LATITUDE)
                val ref = meta.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)
                Log.i(
                    TAG,
                    "Без координат: ${photo.displayName} " +
                        "(GPSLatitude=${lat ?: "нет"}, Ref=${ref ?: "нет"}, " +
                        "дата=${meta.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: "нет"})"
                )
            }

            dao.markMetaDone(photo.id)
            touched++
            done++
        }

        if (batch.isNotEmpty()) {
            Log.i(
                TAG,
                "EXIF: обработано $done из ${batch.size}, дат $withDate, " +
                    "координат $withGeo (из них HEIC по полному файлу $deepRescued)"
            )
        }
        Pass(touched, done)
    }

    /** Источник снимка сейчас недоступен целиком (не отдельный файл). */
    private fun sourceDown(photo: Photo): Boolean =
        photo.sourceId == "smb" && sources.smb.isDown()

    private fun isHeif(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".heic") || lower.endsWith(".heif")
    }

    /**
     * null — файл недоступен или EXIF из него не читается.
     *
     * @param deep читать файл целиком, а не только заголовок.
     */
    /**
     * Разобранный EXIF и, если читали по сети, сами байты заголовка —
     * из них можно достать размеры, когда в EXIF их нет.
     */
    private class Header(val exif: ExifInterface, val bytes: ByteArray?, val file: File? = null)

    private suspend fun openHeader(photo: Photo, deep: Boolean): Header? =
        when (photo.sourceId) {
            // Превью Яндекса идёт без EXIF, но у оригинала он есть — и
            // читается из первых 128 КБ по временной ссылке, как у SMB.
            "yandex" -> readYandexHeader(photo)?.let { Header(ExifInterface(ByteArrayInputStream(it)), it) }

            "smb" -> {
                val bytes = if (deep) {
                    sources.smb.readHeader(photo.uri, DEEP_BYTES)
                } else {
                    sources.smb.readHeader(photo.uri)
                }
                bytes?.let { Header(ExifInterface(ByteArrayInputStream(it)), it) }
            }

            else -> openLocalStream(photo)?.use { Header(ExifInterface(it), null) }
        }

    /**
     * Размеры, если в EXIF их не оказалось. У JPEG они лежат в маркере
     * SOF в самом начале файла — в тех же байтах, что уже прочитаны, —
     * а BitmapFactory умеет вернуть их, не раскодируя картинку. Так
     * закрываются снимки после мессенджеров и редакторов, которые EXIF
     * вырезают: без размеров они никогда не попали бы в пару и не
     * получили бы наезд на лицо в режиме «целиком».
     */
    private fun probeDimensions(header: Header, photo: Photo): Pair<Int, Int>? {
        if (photo.width > 0 && photo.height > 0) return null

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val bytes = header.bytes
        val file = header.file
        when {
            file != null -> BitmapFactory.decodeFile(file.path, opts)
            bytes != null -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            else -> openLocalStream(photo)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        }
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null

        return if (isRotated(header.exif)) h to w else w to h
    }

    private fun isRotated(exif: ExifInterface): Boolean =
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE -> true
            else -> false
        }

    /**
     * Начало оригинала с Яндекс.Диска. Запрос с заголовком Range: сервер
     * отдаёт только запрошенный кусок; если он Range не поддержал и прислал
     * весь файл, читаем всё равно не больше лимита и закрываем поток.
     */
    private suspend fun readYandexHeader(photo: Photo): ByteArray? {
        val url = sources.yandex.downloadUrl(photo) ?: return null
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${HEADER_BYTES - 1}")
            .build()

        return sources.httpClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            body.byteStream().use { input ->
                val buffer = ByteArray(HEADER_BYTES)
                var filled = 0
                while (filled < buffer.size) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read < 0) break
                    filled += read
                }
                if (filled == 0) null else buffer.copyOf(filled)
            }
        }
    }

    /**
     * Поток к локальному файлу. На Android 10+ запрашивается оригинал:
     * без этого система вырезает GPS из EXIF.
     */
    private fun openLocalStream(photo: Photo): InputStream? {
        val uri = Uri.parse(photo.uri)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))
            }.getOrNull()?.let { return it }
        }

        return runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
    }

    /**
     * Размеры кадра из заголовка. У снимков с сетевой папки это
     * единственный источник: обход по SMB размеров не даёт.
     */
    private fun applyDimensions(exif: ExifInterface, photo: Photo): Pair<Int, Int>? {
        if (photo.width > 0 && photo.height > 0) return null

        var w = exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0)
        var h = exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0)
        if (w <= 0 || h <= 0) {
            w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
        }
        if (w <= 0 || h <= 0) return null

        // Снимок, повёрнутый камерой на бок, хранится как горизонтальный,
        // а показывается вертикальным. Для выбора стороны кадрирования
        // важна та ориентация, в которой его увидят.
        return if (isRotated(exif)) h to w else w to h
    }

    private fun exifDate(exif: ExifInterface): Long? {
        val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null

        // Стандартный формат "YYYY:MM:DD HH:MM:SS"; часть камер пишет
        // через дефисы, поэтому пробуем оба.
        for (pattern in DATE_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(raw)?.time
            }.getOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return null
    }

    // ---------- 2. Названия мест ----------

    /** Возвращает число снимков, получивших ответ (включая «места нет»). */
    suspend fun resolvePlaces(limit: Int = 30): Int = withContext(Dispatchers.IO) {
        val batch = dao.photosNeedingPlace(limit)
        var done = 0

        for (photo in batch) {
            val lat = photo.latitude ?: continue
            val lon = photo.longitude ?: continue

            val name = lookup(lat, lon)
            if (name == null) {
                // Временный сбой: в конец очереди, попробуем позже.
                dao.bumpPlaceTries(photo.id)
                continue
            }
            dao.setPlace(photo.id, name)
            done++
        }

        if (batch.isNotEmpty()) Log.i(TAG, "Места: разрешено $done из ${batch.size}")
        done
    }

    /**
     * null — временный сбой; "" — координаты есть, а названия для них нет.
     *
     * Системный геокодер на Android при отсутствии сети возвращает не
     * ошибку, а пустой список. Раньше это записывалось как «места нет»
     * навсегда. Теперь пустой ответ системы — повод спросить открытый
     * сервис, и только его «пусто» считается окончательным.
     */
    private suspend fun lookup(lat: Double, lon: Double): String? {
        // Снимки идут сериями с одной точки, и без кэша каждый стоил бы
        // отдельного запроса с секундной паузой. Три знака после запятой —
        // примерно сто метров, для названия города более чем достаточно.
        val key = "%.3f,%.3f".format(Locale.US, lat, lon)
        placeCache[key]?.let { return it }

        val found = lookupUncached(lat, lon)
        if (found != null) placeCache[key] = found
        return found
    }

    private suspend fun lookupUncached(lat: Double, lon: Double): String? {
        geocoder?.let { g ->
            val address = runCatching { g.getFromLocation(lat, lon, 1) }
                .getOrNull()?.firstOrNull()
            if (address != null) {
                val name = address.locality
                    ?: address.subAdminArea
                    ?: address.adminArea
                    ?: address.countryName
                if (!name.isNullOrBlank()) return name
            }
        }
        return lookupOnline(lat, lon)
    }

    /**
     * Запасной путь для приставок без сервисов Google: OSM Nominatim.
     * Обращения редкие — один раз на снимок за всю жизнь индекса — и с
     * паузой, как того требуют правила сервиса.
     */
    private suspend fun lookupOnline(lat: Double, lon: Double): String? = nominatimLock.withLock {
        // Не чаще одного запроса в секунду на всё приложение: сюда приходят
        // и фоновый проход, и подготовка кадра, у каждого была своя пауза.
        val wait = NOMINATIM_INTERVAL_MS - (System.currentTimeMillis() - lastNominatimAt)
        if (wait > 0) delay(wait)
        lastNominatimAt = System.currentTimeMillis()

        val url = "https://nominatim.openstreetmap.org/reverse" +
            "?format=jsonv2&zoom=10&accept-language=ru&lat=$lat&lon=$lon"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FotoFrame/0.4 (personal Android TV photo frame)")
            .build()

        val body = try {
            sources.httpClient().newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    resp.body?.string()
                } else {
                    Log.w(TAG, "Геокодер ответил ${resp.code}")
                    null
                }
            }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            Log.w(TAG, "Геокодер недоступен: ${e.message}")
            null
        } ?: return@withLock null

        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return@withLock null
        val address = root["address"] as? JsonObject ?: return@withLock ""

        fun field(key: String): String? =
            (address[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

        field("city") ?: field("town") ?: field("village") ?: field("municipality")
            ?: field("county") ?: field("state") ?: field("country") ?: ""
    }

    // ---------- 3. Лица ----------

    /** Одна пачка поиска лиц. */
    suspend fun detectFaces(limit: Int = 40): Pass = withContext(Dispatchers.IO) {
        val batch = dao.photosNeedingFocus(limit, MAX_TRIES)
        var touched = 0
        var done = 0

        for (photo in batch) {
            if (sourceDown(photo)) continue

            val bytes = tryOrNull { fetchFull(photo) }
            if (bytes == null) {
                if (!sourceDown(photo)) {
                    dao.bumpFaceTries(photo.id)
                    touched++
                }
                continue
            }

            // Ориентация из EXIF того же файла. BitmapFactory её не применяет,
            // а показ (Coil) — применяет, поэтому без поворота детектор
            // искал лица на лежащем боком снимке, а найденные координаты
            // относились не к той системе, в которой рисуется кадр.
            val orientation = runCatching {
                ExifInterface(ByteArrayInputStream(bytes))
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

            val bitmap = runCatching { decodeForDetection(bytes, orientation) }.getOrNull()
            if (bitmap == null) {
                // Не смогли разобрать картинку — это не «лиц нет».
                dao.bumpFaceTries(photo.id)
                touched++
                continue
            }

            val focus = try {
                FaceFocusDetector.detect(InputImage.fromBitmap(bitmap, 0), bitmap.width, bitmap.height)
            } catch (e: Throwable) {
                // Сбой детектора — тоже не «лиц нет»: ещё попытка позже.
                e.rethrowIfCancelled()
                Log.w(TAG, "Детектор лиц не сработал на ${photo.displayName}: ${e.message}")
                dao.bumpFaceTries(photo.id)
                touched++
                continue
            } finally {
                bitmap.recycle()
            }

            if (focus != null) {
                dao.setFocus(photo.id, focus.centerX, focus.centerY, focus.faceCount)
            } else {
                // Проверено, лиц нет — больше к снимку не возвращаемся.
                dao.setFocus(photo.id, 0.5f, 0.5f, 0)
            }
            touched++
            done++

            // На экране идёт показ и он качает следующий кадр по той же
            // сети — уступаем ему полосу.
            if (uiVisible) delay(FACE_PAUSE_WHILE_VISIBLE_MS)
        }

        if (batch.isNotEmpty()) Log.i(TAG, "Лица: обработано $done из ${batch.size}")
        Pass(touched, done)
    }

    /**
     * Уменьшенная копия в правильной ориентации: детектору хватает
     * ~1000 px по большей стороне, а поворот такой картинки стоит доли
     * секунды.
     */
    private fun decodeForDetection(bytes: ByteArray, orientation: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return raw
        }

        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (rotated !== raw) raw.recycle()
        return rotated
    }

    /**
     * Полная картинка — нужна только детектору лиц. Сетевая папка читается
     * в память мимо кэша показа, чтобы не вымывать из него текущие кадры.
     */
    private suspend fun fetchFull(photo: Photo): ByteArray? {
        if (photo.sourceId == "smb") return sources.smb.readFile(photo.uri)

        val source = sources.byId(photo.sourceId) ?: return null
        val url = tryOrNull { source.resolveDisplayUrl(photo) } ?: return null

        return tryOrNull {
            if (url.startsWith("http")) {
                val request = Request.Builder().url(url).build()
                sources.httpClient().newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
            } else {
                context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
            }
        }
    }

    companion object {
        /** Один на всё приложение: проходов не должно быть несколько сразу. */
        private val gate = Mutex()

        /**
         * Общий на приложение кэш «координаты → место». Живёт до перезапуска;
         * разобранные места и так лежат в индексе, это лишь чтобы не долбить
         * геокодер по серии кадров с одной точки.
         */
        private val placeCache = ConcurrentHashMap<String, String>()

        private const val TAG = "PhotoEnricher"

        /** После стольких неудач снимок исключается до ручного обновления. */
        const val MAX_TRIES = 3

        /** Потолок второй попытки: столько хватает и крупному HEIC. */
        private const val DEEP_BYTES = 24 * 1024 * 1024

        /** Пауза между снимками в проходе лиц, пока рамка на экране. */
        private const val FACE_PAUSE_WHILE_VISIBLE_MS = 3000L

        /** Сколько снимков без координат разобрать в лог подробно. */
        private const val LOG_SAMPLES = 5
        private val DATE_PATTERNS = listOf("yyyy:MM:dd HH:mm:ss", "yyyy-MM-dd HH:mm:ss")

        /** Сколько пачек подряд без единого успеха терпит проход. */
        private const val BARREN_LIMIT = 3

        /** Большая сторона картинки для детектора: ему хватает ~1000 px. */
        private const val DETECT_SIZE = 1024

        /** Начало оригинала с Яндекса — столько же, сколько у SMB. */
        private const val HEADER_BYTES = 128 * 1024

        /** Правила Nominatim: не чаще одного запроса в секунду. */
        private const val NOMINATIM_INTERVAL_MS = 1100L
        private val nominatimLock = Mutex()
        @Volatile private var lastNominatimAt = 0L
    }
}
