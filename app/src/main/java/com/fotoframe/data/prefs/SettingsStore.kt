package com.fotoframe.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class PhotoOrder {
    /** Случайно, все снимки равноправны. */
    RANDOM,

    /** Случайно, но новые заметно чаще. */
    RANDOM_RECENT_FIRST,

    /** Строго от новых к старым. */
    NEWEST_FIRST
}

enum class Transition {
    CROSSFADE,
    SLIDE,
    ZOOM_BLUR,
    PUSH_UP,

    /** Кадры как грани куба: старый уходит в глубину, новый выезжает. */
    CUBE,

    /** Новый кадр раскрывается кругом из центра. */
    CIRCLE,

    /** Новый кадр наезжает шторкой слева направо. */
    WIPE,

    /** Смена через белую вспышку. */
    FLASH,

    RANDOM
}

/** Как кадр вписывается в экран. */
enum class FitMode {
    /** Целиком, поля закрашиваются размытым фоном из самого снимка. */
    FIT_BLUR,

    /** Целиком, поля чёрные. */
    FIT_BLACK,

    /** Заполнить экран, края обрезаются. */
    FILL,

    /** Заполнить и медленно двигать кадр — эффект Кена Бёрнса. */
    KEN_BURNS
}

data class SlideshowSettings(
    val intervalSeconds: Int = 20,
    val order: PhotoOrder = PhotoOrder.RANDOM_RECENT_FIRST,
    val transition: Transition = Transition.RANDOM,
    val transitionMillis: Int = 1200,
    val fitMode: FitMode = FitMode.FIT_BLUR,

    /**
     * Наезд на лицо. Работает поверх режима «во весь экран с наездом»:
     * если на снимке найдено лицо, камера ведёт к нему, а не к центру.
     */
    val faceFocus: Boolean = true,

    /**
     * Наезд на лицо и в режимах «целиком». Кадр появляется весь, с полями,
     * и за время показа медленно увеличивается к лицу — ровно настолько,
     * чтобы поля ушли. Только для горизонтальных снимков с найденным лицом.
     */
    val fitFaceZoom: Boolean = true,

    /**
     * Глубина наезда: во сколько раз кадр увеличивается за показ.
     * 1.0 — движения нет. Скорость отдельной настройкой не задаётся:
     * наезд всегда растянут на время показа, поэтому его темп задаётся
     * интервалом смены, а этим ползунком — насколько далеко он уедет.
     */
    val zoomStrength: Float = 1.12f,

    /**
     * Доля показа, за которую наезд успевает пройти. 1.0 — движение
     * растянуто на весь кадр (спокойно), 0.3 — заканчивается за треть
     * показа и дальше кадр стоит крупным планом (заметно). При коротком
     * интервале только этим и получается сделать наезд быстрым.
     */
    val zoomPace: Float = 1f,

    /**
     * Наезд в режимах «целиком» и без лица в кадре — тогда просто от
     * центра. Иначе такие снимки стоят неподвижно.
     */
    val zoomWithoutFace: Boolean = true,

    /**
     * Пропускать серии почти одинаковых кадров: из десяти снимков с одной
     * точки за цикл показывается один. Похожесть — по перцептивному хэшу,
     * серия — та же папка и полторы минуты по времени съёмки.
     */
    val skipSimilar: Boolean = true,

    /**
     * Подбирать пару вертикальных по содержимому: снимок с людьми — к
     * снимку с людьми, без людей — к такому же. Иначе рядом с портретом
     * оказывается тарелка с ужином, и пара выглядит случайной, каковой
     * и является. Работает по числу найденных лиц; снимки, у которых
     * лица ещё не искали, в расчёт не берутся и подбираются как раньше.
     */
    val pairByContent: Boolean = true,

    /** Погода поверх кадра, рядом с часами. */
    val showWeather: Boolean = false,

    /**
     * Координаты для погоды. Задаются поиском города в настройках —
     * вводить широту и долготу с пульта никто не станет.
     */
    val weatherLat: Float = 0f,
    val weatherLon: Float = 0f,

    /** Название места, чтобы в настройках было видно, что выбрано. */
    val weatherPlace: String = "",

    /**
     * Личный ключ Яндекс Погоды, необязательный. Бесплатный тариф
     * «Погода для умного дома» — только для некоммерческого использования,
     * ключ у каждого свой. Задан — Яндекс спрашивается первым.
     */
    val yandexWeatherKey: String = "",

    /**
     * Как часто спрашивать Яндекс, в часах. У бесплатного тарифа 30
     * запросов в сутки на ключ, и полчаса, как у бесплатных поставщиков,
     * — это 48 на одно устройство. Раз в два часа — 12 на устройство:
     * укладываются и два устройства на одном ключе.
     */
    val yandexRefreshHours: Int = 2,

    /** Дописывать город в строку погоды на экране. */
    val showWeatherCity: Boolean = false,

    /**
     * Насколько сильно новые снимки вытесняют старые.
     * 1 — без приоритета, 5 — половина показов из свежих 4% коллекции.
     */
    val recencyBias: Float = 3f,

    /** Доля показов, отдаваемая пулу ещё не виденных новинок. */
    val freshBoost: Float = 0.3f,

    /** Сколько дней снимок считается новинкой. */
    val freshWindowDays: Int = 14,

    val showClock: Boolean = true,
    val showDate: Boolean = true,
    val showPhotoDate: Boolean = true,
    val showLocation: Boolean = true,

    /**
     * Имя файла под снимком. Для коллекций картин это по сути название
     * работы: «Айвазовский_Девятый_вал.jpg». Показывается без расширения,
     * подчёркивания — пробелами.
     */
    val showFileName: Boolean = false,
    val pairPortraits: Boolean = true,

    /**
     * Вертикальные снимки показывать целиком, даже когда для остальных
     * выбрано заполнение экрана. Вертикальный кадр на широком экране при
     * обрезке теряет около половины изображения, и это почти всегда хуже
     * полей по бокам.
     */
    val portraitFitWhole: Boolean = true,
    val keepScreenOn: Boolean = true,

    /** Не показывать снимок, если он был на экране за последние N дней. 0 — без ограничения. */
    val repeatCooldownDays: Int = 30,

    // --- Что не пускать на экран ---

    /** Минимальное разрешение в мегапикселях. 0 — не проверять. */
    val minMegapixels: Float = 2f,

    /** Минимальный вес файла в килобайтах. Отсекает иконки и мелкую графику. */
    val minFileSizeKb: Int = 300,

    /** Скриншоты, загрузки, папки мессенджеров. */
    val skipJunkFolders: Boolean = true,

    /** PNG почти всегда графика, а не снимок с камеры. */
    val skipPng: Boolean = true,

    /**
     * Предельное соотношение сторон. Отсекает штрихкоды, чеки, узкие
     * панорамы и длинные скриншоты переписки.
     */
    val maxAspectRatio: Float = 2.2f,

    val yandexToken: String? = null,
    val yandexFolder: String = "disk:/",

    // --- Сетевая папка (SMB2/SMB3) ---
    /** Адрес хранилища: IP или имя, без smb:// и без имени шары. */
    val smbHost: String = "",
    /** Имя общей папки верхнего уровня, например Photo или media. */
    val smbShare: String = "",
    /** Пустой пользователь означает гостевой доступ. */
    val smbUser: String = "",
    val smbPassword: String = "",
    val smbDomain: String = "",
    /** Подпапка внутри шары. Пусто — вся шара целиком. */
    val smbFolder: String = ""
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {

    private object Keys {
        val interval = intPreferencesKey("interval_seconds")
        val order = stringPreferencesKey("photo_order")
        val transition = stringPreferencesKey("transition")
        val transitionMillis = intPreferencesKey("transition_millis")
        val fitMode = stringPreferencesKey("fit_mode")
        val faceFocus = booleanPreferencesKey("face_focus")
        val fitFaceZoom = booleanPreferencesKey("fit_face_zoom")
        val zoomStrength = floatPreferencesKey("zoom_strength")
        val zoomPace = floatPreferencesKey("zoom_pace")
        val zoomWithoutFace = booleanPreferencesKey("zoom_without_face")
        val skipSimilar = booleanPreferencesKey("skip_similar")
        val pairByContent = booleanPreferencesKey("pair_by_content")
        val showWeather = booleanPreferencesKey("show_weather")
        val weatherLat = floatPreferencesKey("weather_lat")
        val weatherLon = floatPreferencesKey("weather_lon")
        val weatherPlace = stringPreferencesKey("weather_place")
        val yandexWeatherKey = stringPreferencesKey("yandex_weather_key")
        val yandexRefreshHours = intPreferencesKey("yandex_refresh_hours")
        val showWeatherCity = booleanPreferencesKey("show_weather_city")
        val recencyBias = floatPreferencesKey("recency_bias")
        val freshBoost = floatPreferencesKey("fresh_boost")
        val freshWindowDays = intPreferencesKey("fresh_window_days")
        val showClock = booleanPreferencesKey("show_clock")
        val showDate = booleanPreferencesKey("show_date")
        val showPhotoDate = booleanPreferencesKey("show_photo_date")
        val showLocation = booleanPreferencesKey("show_location")
        val showFileName = booleanPreferencesKey("show_file_name")
        val pairPortraits = booleanPreferencesKey("pair_portraits")
        val portraitFitWhole = booleanPreferencesKey("portrait_fit_whole")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val repeatCooldownDays = intPreferencesKey("repeat_cooldown_days")
        val minMegapixels = floatPreferencesKey("min_megapixels")
        val minFileSizeKb = intPreferencesKey("min_file_size_kb")
        val skipJunkFolders = booleanPreferencesKey("skip_junk_folders")
        val skipPng = booleanPreferencesKey("skip_png")
        val maxAspectRatio = floatPreferencesKey("max_aspect_ratio")
        val yandexToken = stringPreferencesKey("yandex_token")
        val yandexFolder = stringPreferencesKey("yandex_folder")
        val smbHost = stringPreferencesKey("smb_host")
        val smbShare = stringPreferencesKey("smb_share")
        val smbUser = stringPreferencesKey("smb_user")
        val smbPassword = stringPreferencesKey("smb_password")
        val smbDomain = stringPreferencesKey("smb_domain")
        val smbFolder = stringPreferencesKey("smb_folder")

        /** Служебное, в SlideshowSettings не входит: показ на это не реагирует. */
        val lastIndexAt = longPreferencesKey("last_index_at")
    }

    val settings: Flow<SlideshowSettings> = context.dataStore.data.map { p ->
        val defaults = SlideshowSettings()
        SlideshowSettings(
            intervalSeconds = p[Keys.interval] ?: defaults.intervalSeconds,
            order = p[Keys.order]?.let { runCatching { PhotoOrder.valueOf(it) }.getOrNull() }
                ?: defaults.order,
            transition = p[Keys.transition]?.let { runCatching { Transition.valueOf(it) }.getOrNull() }
                ?: defaults.transition,
            transitionMillis = p[Keys.transitionMillis] ?: defaults.transitionMillis,
            fitMode = p[Keys.fitMode]?.let { runCatching { FitMode.valueOf(it) }.getOrNull() }
                ?: defaults.fitMode,
            faceFocus = p[Keys.faceFocus] ?: defaults.faceFocus,
            fitFaceZoom = p[Keys.fitFaceZoom] ?: defaults.fitFaceZoom,
            zoomStrength = p[Keys.zoomStrength] ?: defaults.zoomStrength,
            zoomPace = p[Keys.zoomPace] ?: defaults.zoomPace,
            zoomWithoutFace = p[Keys.zoomWithoutFace] ?: defaults.zoomWithoutFace,
            skipSimilar = p[Keys.skipSimilar] ?: defaults.skipSimilar,
            pairByContent = p[Keys.pairByContent] ?: defaults.pairByContent,
            showWeather = p[Keys.showWeather] ?: defaults.showWeather,
            weatherLat = p[Keys.weatherLat] ?: defaults.weatherLat,
            weatherLon = p[Keys.weatherLon] ?: defaults.weatherLon,
            weatherPlace = p[Keys.weatherPlace] ?: defaults.weatherPlace,
            yandexWeatherKey = p[Keys.yandexWeatherKey] ?: defaults.yandexWeatherKey,
            yandexRefreshHours = p[Keys.yandexRefreshHours] ?: defaults.yandexRefreshHours,
            showWeatherCity = p[Keys.showWeatherCity] ?: defaults.showWeatherCity,
            recencyBias = p[Keys.recencyBias] ?: defaults.recencyBias,
            freshBoost = p[Keys.freshBoost] ?: defaults.freshBoost,
            freshWindowDays = p[Keys.freshWindowDays] ?: defaults.freshWindowDays,
            showClock = p[Keys.showClock] ?: defaults.showClock,
            showDate = p[Keys.showDate] ?: defaults.showDate,
            showPhotoDate = p[Keys.showPhotoDate] ?: defaults.showPhotoDate,
            showLocation = p[Keys.showLocation] ?: defaults.showLocation,
            showFileName = p[Keys.showFileName] ?: defaults.showFileName,
            pairPortraits = p[Keys.pairPortraits] ?: defaults.pairPortraits,
            portraitFitWhole = p[Keys.portraitFitWhole] ?: defaults.portraitFitWhole,
            keepScreenOn = p[Keys.keepScreenOn] ?: defaults.keepScreenOn,
            repeatCooldownDays = p[Keys.repeatCooldownDays] ?: defaults.repeatCooldownDays,
            minMegapixels = p[Keys.minMegapixels] ?: defaults.minMegapixels,
            minFileSizeKb = p[Keys.minFileSizeKb] ?: defaults.minFileSizeKb,
            skipJunkFolders = p[Keys.skipJunkFolders] ?: defaults.skipJunkFolders,
            skipPng = p[Keys.skipPng] ?: defaults.skipPng,
            maxAspectRatio = p[Keys.maxAspectRatio] ?: defaults.maxAspectRatio,
            yandexToken = p[Keys.yandexToken],
            yandexFolder = p[Keys.yandexFolder] ?: defaults.yandexFolder,
            smbHost = p[Keys.smbHost] ?: defaults.smbHost,
            smbShare = p[Keys.smbShare] ?: defaults.smbShare,
            smbUser = p[Keys.smbUser] ?: defaults.smbUser,
            smbPassword = p[Keys.smbPassword] ?: defaults.smbPassword,
            smbDomain = p[Keys.smbDomain] ?: defaults.smbDomain,
            smbFolder = p[Keys.smbFolder] ?: defaults.smbFolder
        )
    }

    suspend fun setInterval(seconds: Int) = edit { it[Keys.interval] = seconds }
    suspend fun setOrder(order: PhotoOrder) = edit { it[Keys.order] = order.name }
    suspend fun setTransition(t: Transition) = edit { it[Keys.transition] = t.name }
    suspend fun setTransitionMillis(ms: Int) = edit { it[Keys.transitionMillis] = ms }
    suspend fun setFitMode(mode: FitMode) = edit { it[Keys.fitMode] = mode.name }
    suspend fun setFaceFocus(v: Boolean) = edit { it[Keys.faceFocus] = v }
    suspend fun setFitFaceZoom(v: Boolean) = edit { it[Keys.fitFaceZoom] = v }
    suspend fun setZoomStrength(v: Float) = edit { it[Keys.zoomStrength] = v }
    suspend fun setZoomPace(v: Float) = edit { it[Keys.zoomPace] = v }
    suspend fun setZoomWithoutFace(v: Boolean) = edit { it[Keys.zoomWithoutFace] = v }
    suspend fun setSkipSimilar(v: Boolean) = edit { it[Keys.skipSimilar] = v }
    suspend fun setPairByContent(v: Boolean) = edit { it[Keys.pairByContent] = v }
    suspend fun setShowWeather(v: Boolean) = edit { it[Keys.showWeather] = v }

    suspend fun setYandexWeatherKey(v: String) = edit { it[Keys.yandexWeatherKey] = v.trim() }
    suspend fun setYandexRefreshHours(v: Int) = edit { it[Keys.yandexRefreshHours] = v.coerceIn(1, 6) }
    suspend fun setShowWeatherCity(v: Boolean) = edit { it[Keys.showWeatherCity] = v }

    suspend fun setWeatherPlace(name: String, lat: Float, lon: Float) = edit {
        it[Keys.weatherPlace] = name
        it[Keys.weatherLat] = lat
        it[Keys.weatherLon] = lon
    }
    suspend fun setRecencyBias(value: Float) = edit { it[Keys.recencyBias] = value }
    suspend fun setFreshBoost(value: Float) = edit { it[Keys.freshBoost] = value }
    suspend fun setFreshWindowDays(days: Int) = edit { it[Keys.freshWindowDays] = days }
    suspend fun setShowClock(v: Boolean) = edit { it[Keys.showClock] = v }
    suspend fun setShowDate(v: Boolean) = edit { it[Keys.showDate] = v }
    suspend fun setShowPhotoDate(v: Boolean) = edit { it[Keys.showPhotoDate] = v }
    suspend fun setShowLocation(v: Boolean) = edit { it[Keys.showLocation] = v }
    suspend fun setShowFileName(v: Boolean) = edit { it[Keys.showFileName] = v }
    suspend fun setPairPortraits(v: Boolean) = edit { it[Keys.pairPortraits] = v }
    suspend fun setPortraitFitWhole(v: Boolean) = edit { it[Keys.portraitFitWhole] = v }
    suspend fun setKeepScreenOn(v: Boolean) = edit { it[Keys.keepScreenOn] = v }
    suspend fun setRepeatCooldownDays(days: Int) = edit { it[Keys.repeatCooldownDays] = days }
    suspend fun setMinMegapixels(v: Float) = edit { it[Keys.minMegapixels] = v }
    suspend fun setMinFileSizeKb(v: Int) = edit { it[Keys.minFileSizeKb] = v }
    suspend fun setSkipJunkFolders(v: Boolean) = edit { it[Keys.skipJunkFolders] = v }
    suspend fun setSkipPng(v: Boolean) = edit { it[Keys.skipPng] = v }
    suspend fun setMaxAspectRatio(v: Float) = edit { it[Keys.maxAspectRatio] = v }
    suspend fun setYandexToken(token: String?) = edit {
        if (token.isNullOrBlank()) it.remove(Keys.yandexToken) else it[Keys.yandexToken] = token
    }
    suspend fun setYandexFolder(path: String) = edit { it[Keys.yandexFolder] = path }

    suspend fun setSmbFolder(path: String) = edit { it[Keys.smbFolder] = path }

    suspend fun setSmb(host: String, share: String, user: String, password: String, folder: String) =
        edit {
            it[Keys.smbHost] = host.trim()
            it[Keys.smbShare] = share.trim()
            it[Keys.smbUser] = user.trim()
            it[Keys.smbPassword] = password
            it[Keys.smbFolder] = folder.trim()
        }

    /** Когда индекс обновлялся в последний раз — чтобы не обходить источники при каждом запуске. */
    suspend fun lastIndexAt(): Long = context.dataStore.data.first()[Keys.lastIndexAt] ?: 0L
    suspend fun setLastIndexAt(at: Long) = edit { it[Keys.lastIndexAt] = at }

    /**
     * Корень, по которому источник обходился в последний раз целиком.
     * Когда пользователь выбирает другую папку, старые записи исчезают
     * из обхода тысячами — и защита от массового удаления в Indexer
     * приняла бы это за сбой. Сравнение с сохранённым корнем позволяет
     * отличить смену папки от отвалившегося хранилища.
     */
    suspend fun indexedRoot(sourceId: String): String? =
        context.dataStore.data.first()[stringPreferencesKey("indexed_root_$sourceId")]

    suspend fun setIndexedRoot(sourceId: String, root: String) =
        edit { it[stringPreferencesKey("indexed_root_$sourceId")] = root }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
