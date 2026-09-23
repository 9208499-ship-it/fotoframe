package com.fotoframe.engine

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.ImageRequest
import com.fotoframe.App
import com.fotoframe.data.db.Photo
import com.fotoframe.source.DeleteResult
import com.fotoframe.source.SmbSource
import com.fotoframe.data.prefs.SlideshowSettings
import com.fotoframe.source.Folder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Кадр на экране. Обычно это один снимок, но два вертикальных
 * показываются рядом: поодиночке такой снимок либо теряет при обрезке
 * половину изображения, либо оставляет широкие поля по бокам, а вдвоём
 * они заполняют экран целиком.
 */
data class Slide(
    val photo: Photo,
    val displayUrl: String,
    val second: Photo? = null,
    val secondUrl: String? = null
) {
    val isPair: Boolean get() = second != null && secondUrl != null

    /** Все снимки кадра — например, чтобы отметить показанными. */
    val photos: List<Photo> get() = if (second != null) listOf(photo, second) else listOf(photo)
}

/**
 * Состояние обзора папок.
 *
 * Один обзор обслуживает любой источник: и сетевую папку, и Яндекс.Диск —
 * оба умеют перечислять подпапки через общий интерфейс.
 */
data class BrowseState(
    val visible: Boolean = false,
    val sourceId: String = "",
    val title: String = "",
    /** Текущий путь внутри источника. */
    val path: String = "",
    /** Путь, откуда пришли, — для кнопки «наверх». */
    val stack: List<String> = emptyList(),
    val entries: List<Folder> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

data class SlideshowState(
    val current: Slide? = null,
    val settings: SlideshowSettings = SlideshowSettings(),
    val totalPhotos: Int = 0,
    val paused: Boolean = false,
    val message: String? = null,

    /** Пользователь ушёл назад по истории — показ на паузе, пока не вернётся. */
    val inHistory: Boolean = false,

    /**
     * Ручное увеличение с пульта. 1 — как есть. Пока кадр увеличен,
     * автосмена стоит: рассматривать снимок и одновременно ждать, что он
     * уедет по таймеру, — плохое сочетание.
     */
    val zoom: Float = 1f,

    /** Смещение увеличенного кадра, -1..1 — доля от максимально возможного сдвига. */
    val panX: Float = 0f,
    val panY: Float = 0f,

    /** Меню действий над кадром. Пока открыто, показ стоит. */
    val menu: ActionMenu? = null,

    /** Системный диалог удаления, который должна показать Activity. */
    val pendingDelete: PendingDelete? = null,

    /** Погода для подписи; null — выключена или пока не загрузилась. */
    val weather: Weather? = null
) {
    val zoomed: Boolean get() = zoom > 1.01f
}

/** Город из поиска для погоды. */
data class City(val name: String, val region: String, val lat: Float, val lon: Float)

/** Запрос системного подтверждения на удаление локального файла. */
data class PendingDelete(val photoId: Long, val request: android.app.PendingIntent)

/**
 * Пункт меню действий. [photoId] — скрыть этот снимок; [openSettings] —
 * открыть настройки (делает Activity); ни того ни другого — закрыть меню.
 */
data class MenuItem(
    val label: String,
    val photoId: Long? = null,
    val openSettings: Boolean = false,
    /** Убрать файл из хранилища. Требует подтверждения — см. [ActionMenu.confirm]. */
    val deleteId: Long? = null,
    /** Повернуть снимок на [rotateBy] градусов. Меню при этом остаётся открытым. */
    val rotateId: Long? = null,
    val rotateBy: Int = 0
)

/**
 * @param confirm true — это второй шаг: подтверждение удаления файла.
 *   Удаление в один клик с пульта — слишком лёгкий способ потерять снимок.
 * @param subtitle пояснение над списком (имя файла при подтверждении)
 */
data class ActionMenu(
    val items: List<MenuItem>,
    val selected: Int = 0,
    val confirm: Boolean = false,
    val subtitle: String? = null
)

class SlideshowViewModel(app: Application) : AndroidViewModel(app) {

    private val application = app as App
    private val dao = application.database.photoDao()
    private val picker = PhotoPicker(dao)
    private val indexer = Indexer(dao, application.settingsStore)
    private val filter = ContentFilter(dao)
    private val enricher = PhotoEnricher(application, dao, application.sources)

    private val _state = MutableStateFlow(SlideshowState())
    val state: StateFlow<SlideshowState> = _state.asStateFlow()

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    private var loop: Job? = null

    /** Когда сменился кадр в последний раз. От этого момента идёт отсчёт. */
    private var lastAdvanceAt = 0L

    /** Следующий кадр готовится, пока показывается текущий. */
    /**
     * Очередь готовых кадров. Готовый — значит ссылка получена, файл
     * скачан, дата и лица разобраны; у ближайших [MEMORY_AHEAD] картинка
     * ещё и раскодирована в память. Держать раскодированными все нельзя:
     * на 4K это по 33 МБ на кадр. А скачанные лежат на диске и
     * раскодируются за долю секунды — этого хватает, чтобы перемотка
     * вперёд не упиралась в сеть.
     */
    private val queue = ArrayDeque<Slide>()
    private var fillJob: Job? = null

    /** Ручное обновление уже идёт — повторное нажатие не запускает второе. */
    private var indexing = false

    /**
     * Лента показанных кадров. Кнопка «влево» отматывает по ней назад,
     * «вправо» — вперёд и дальше к новым. Держим два десятка: этого хватает,
     * чтобы вернуться к понравившемуся снимку, и не растёт память.
     */
    private val history = ArrayDeque<Slide>()

    /** Где мы в истории. -1 значит «на живом краю», история не листается. */
    private var historyPos = -1

    /**
     * Смена кадра — по одному за раз. Её вызывают и цикл по таймеру, и
     * кнопка «вправо», и ошибка загрузки; без замка две смены могли идти
     * одновременно и отмечать показанными два снимка за один интервал.
     */
    private val advanceLock = Mutex()

    /** Кадров подряд, которые не удалось загрузить. */
    private var loadFailures = 0

    private var weatherJob: Job? = null

    private val weatherService = WeatherService(application.sources)

    /** Найденные города для выбора в настройках. */
    private val _cities = MutableStateFlow<List<City>>(emptyList())
    val cities: StateFlow<List<City>> = _cities.asStateFlow()

    private val _hidden = MutableStateFlow<List<Photo>>(emptyList())

    /** Скрытые с пульта снимки — для списка в настройках. */
    val hiddenPhotos: StateFlow<List<Photo>> = _hidden.asStateFlow()

    init {
        viewModelScope.launch {
            var lastSources: Triple<String?, String, String>? = null
            application.settingsStore.settings.collect { s ->
                _state.value = _state.value.copy(settings = s)

                // Источник подключили или отключили — пересчитать допуск,
                // чтобы его снимки сразу вошли в показ или вышли из него.
                val sources = Triple(s.yandexToken, s.smbHost, s.smbShare)
                if (lastSources != null && sources != lastSources) {
                    tryOrNull { filter.apply(s) }
                    clearQueue()
                }
                lastSources = sources
            }
        }
        viewModelScope.launch {
            dao.observeCount().collect { count ->
                _state.value = _state.value.copy(totalPhotos = count)
            }
        }
    }

    /**
     * Цикл показа. Просыпается не реже раза в секунду и сверяется с
     * [lastAdvanceAt]: так пропуск кадра кнопкой начинает отсчёт заново,
     * а смена интервала в настройках действует сразу, а не со следующего
     * кадра.
     */
    fun start() {
        startWeather()
        if (loop?.isActive == true) return
        loop = viewModelScope.launch {
            advance()
            while (true) {
                val s = _state.value
                if (s.paused || s.inHistory || s.zoomed || s.menu != null) {
                    // Стоим: снимок, к которому вернулись или который поставили
                    // на паузу, не должен уехать сразу после возобновления.
                    lastAdvanceAt = System.currentTimeMillis()
                    delay(500)
                    continue
                }
                // Пока кадра на экране нет, пробуем чаще: ждать полный
                // интервал перед пустым экраном незачем.
                val interval = if (s.current == null) EMPTY_RETRY_MS
                else s.settings.intervalSeconds * 1000L
                val due = lastAdvanceAt + interval
                val wait = due - System.currentTimeMillis()
                if (wait > 0) {
                    delay(wait.coerceAtMost(1000))
                    continue
                }
                // Сторож: если кадр висит втрое дольше интервала, значит
                // предыдущая смена где-то застряла. Пишем в лог и меняем
                // кадр принудительно — рамка на стене не должна замирать
                // из-за одной неудачной подготовки.
                val stuckFor = System.currentTimeMillis() - lastAdvanceAt
                if (stuckFor > s.settings.intervalSeconds * 3000L) {
                    Log.w(TAG, "Кадр висит ${stuckFor / 1000} с — принудительная смена")
                }
                advance()
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        fillJob?.cancel()
    }

    /**
     * Центральная кнопка. В обычном режиме — пауза. Если листали историю —
     * возврат к живому показу с того кадра, что на экране.
     */
    fun togglePause() {
        val s = _state.value
        if (s.inHistory) {
            historyPos = -1
            lastAdvanceAt = System.currentTimeMillis()
            _state.value = s.copy(inHistory = false, paused = false)
        } else {
            _state.value = s.copy(paused = !s.paused)
        }
    }

    // ---------- Увеличение с пульта ----------

    /** Следующая ступень масштаба. На последней ступени ничего не меняет. */
    fun zoomIn() {
        val s = _state.value
        if (s.current == null) return
        val next = ZOOM_STEPS.firstOrNull { it > s.zoom + 0.01f } ?: return
        _state.value = s.copy(zoom = next)
    }

    /** Ступень назад; с первой ступени — возврат к обычному показу. */
    fun zoomOut() {
        val s = _state.value
        val prev = ZOOM_STEPS.lastOrNull { it < s.zoom - 0.01f } ?: return
        if (prev <= 1f) resetZoom() else _state.value = s.copy(zoom = prev)
    }

    /**
     * Сдвиг увеличенного кадра. Шаг — четверть максимального хода, так что
     * от края до края восемь нажатий. За пределы кадра не выходит.
     */
    fun pan(dx: Float, dy: Float) {
        val s = _state.value
        if (!s.zoomed) return
        _state.value = s.copy(
            panX = (s.panX + dx).coerceIn(-1f, 1f),
            panY = (s.panY + dy).coerceIn(-1f, 1f)
        )
    }

    /** Обычный масштаб. Отсчёт интервала начинается заново. */
    fun resetZoom() {
        val s = _state.value
        if (!s.zoomed) return
        lastAdvanceAt = System.currentTimeMillis()
        _state.value = s.copy(zoom = 1f, panX = 0f, panY = 0f)
    }

    // ---------- Меню действий и скрытие ----------

    /** Долгое нажатие OK: меню над текущим кадром. */
    fun openMenu() {
        val s = _state.value
        val slide = s.current ?: return
        if (s.menu != null) return

        val second = slide.second
        val hide = if (second != null) {
            listOf(
                MenuItem("Скрыть левый снимок", photoId = slide.photo.id),
                MenuItem("Скрыть правый снимок", photoId = second.id)
            )
        } else {
            listOf(MenuItem("Скрыть этот снимок", photoId = slide.photo.id))
        }
        val remove = if (second != null) {
            listOf(
                MenuItem("Удалить левый с хранилища", deleteId = slide.photo.id),
                MenuItem("Удалить правый с хранилища", deleteId = second.id)
            )
        } else {
            listOf(MenuItem("Удалить с хранилища", deleteId = slide.photo.id))
        }
        // Поворот — для первого снимка кадра; у пары второй поворачивается
        // из того же меню отдельными пунктами.
        val rotate = listOf(
            MenuItem("Повернуть по часовой", rotateId = slide.photo.id, rotateBy = 90),
            MenuItem("Повернуть против часовой", rotateId = slide.photo.id, rotateBy = -90)
        ) + if (second != null) {
            listOf(MenuItem("Повернуть правый по часовой", rotateId = second.id, rotateBy = 90))
        } else {
            emptyList()
        }
        val items = hide + remove + rotate + MenuItem("Настройки", openSettings = true) + MenuItem("Отмена")
        _state.value = s.copy(menu = ActionMenu(items, selected = 0))
    }

    /** Второй шаг: подтверждение удаления конкретного файла. */
    private fun askConfirm(photo: Photo) {
        val where = when (photo.sourceId) {
            "smb" -> "Файл переедет в папку «${SmbSource.TRASH_DIR}» на сетевом хранилище"
            "yandex" -> "Файл переедет в Корзину Яндекс.Диска"
            else -> "Файл будет удалён с устройства"
        }
        _state.value = _state.value.copy(
            menu = ActionMenu(
                items = listOf(
                    MenuItem("Нет, оставить"),
                    MenuItem("Да, удалить", deleteId = photo.id)
                ),
                selected = 0,
                confirm = true,
                subtitle = "${photo.displayName}\n$where"
            )
        )
    }

    fun closeMenu() {
        val s = _state.value
        if (s.menu == null) return
        lastAdvanceAt = System.currentTimeMillis()
        _state.value = s.copy(menu = null)
    }

    /**
     * Выбор пункта пальцем: подсветить и сразу выполнить. На пульте те же
     * два шага разнесены на стрелки и OK.
     */
    fun menuPick(index: Int): MenuItem? {
        val s = _state.value
        val m = s.menu ?: return null
        if (index !in m.items.indices) return null
        _state.value = s.copy(menu = m.copy(selected = index))
        return menuSelect()
    }

    fun menuMove(delta: Int) {
        val s = _state.value
        val m = s.menu ?: return
        val next = (m.selected + delta).mod(m.items.size)
        _state.value = s.copy(menu = m.copy(selected = next))
    }

    /**
     * Выполнить выбранный пункт. Возвращает его — Activity решает, что
     * делать с настройками и с системным диалогом удаления.
     */
    fun menuSelect(): MenuItem? {
        val m = _state.value.menu ?: return null
        val item = m.items.getOrNull(m.selected) ?: return null

        when {
            item.rotateId != null -> rotate(item.rotateId, item.rotateBy)
            item.photoId != null -> hide(item.photoId)
            item.deleteId != null && !m.confirm -> {
                // Первый выбор — только спросить.
                val slide = _state.value.current
                val photo = when (item.deleteId) {
                    slide?.photo?.id -> slide.photo
                    slide?.second?.id -> slide.second
                    else -> null
                }
                if (photo != null) askConfirm(photo) else closeMenu()
            }
            item.deleteId != null -> deleteFile(item.deleteId)
            else -> closeMenu()
        }
        return item
    }

    /**
     * Убрать файл из хранилища. Локальные снимки на Android 11+ требуют
     * системного диалога — тогда состояние получает [SlideshowState.pendingDelete],
     * и его показывает Activity.
     */
    fun deleteFile(photoId: Long) {
        viewModelScope.launch {
            val slide = _state.value.current
            val photo = when (photoId) {
                slide?.photo?.id -> slide.photo
                slide?.second?.id -> slide.second
                else -> null
            } ?: dao.photoById(photoId)

            if (photo == null) {
                _state.value = _state.value.copy(menu = null)
                return@launch
            }

            val source = application.sources.byId(photo.sourceId)
            val result = if (source == null) {
                DeleteResult.Failed("Источник ${photo.sourceId} не найден")
            } else {
                try {
                    source.delete(photo)
                } catch (e: Throwable) {
                    e.rethrowIfCancelled()
                    DeleteResult.Failed(e.message ?: "Не удалось удалить")
                }
            }

            when (result) {
                is DeleteResult.Done -> {
                    forget(photo.id)
                    _state.value = _state.value.copy(
                        menu = null,
                        message = "${photo.displayName}: ${result.note}"
                    )
                    clearMessageLater()
                }
                is DeleteResult.NeedsConfirmation -> {
                    _state.value = _state.value.copy(
                        menu = null,
                        pendingDelete = PendingDelete(photo.id, result.request)
                    )
                }
                is DeleteResult.Failed -> {
                    _state.value = _state.value.copy(menu = null, message = result.reason)
                    clearMessageLater()
                }
                is DeleteResult.Unsupported -> {
                    _state.value = _state.value.copy(menu = null, message = result.reason)
                    clearMessageLater()
                }
            }
        }
    }

    /** Системный диалог показан и получен ответ. */
    fun onDeleteConfirmed(photoId: Long, granted: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(pendingDelete = null)
            if (!granted) return@launch
            forget(photoId)
            _state.value = _state.value.copy(message = "Снимок удалён с устройства")
            clearMessageLater()
        }
    }

    /** Файла больше нет: убрать из индекса, из истории и с экрана. */
    private suspend fun forget(photoId: Long) {
        dao.deleteById(photoId)

        val kept = history.filterNot { it.contains(photoId) }
        history.clear()
        history.addAll(kept)
        historyPos = -1
        dropFromQueue(photoId)

        if (_state.value.current?.contains(photoId) == true) {
            _state.value = _state.value.copy(inHistory = false)
            advance()
        }
    }

    private fun clearMessageLater() {
        viewModelScope.launch {
            delay(MESSAGE_MS)
            val s = _state.value
            if (s.current != null) _state.value = s.copy(message = null)
        }
    }

    /**
     * Повернуть снимок на [delta] градусов, навсегда. Обновляется и запись,
     * и то, что на экране: кадр перерисовывается сразу, меню остаётся
     * открытым — часто нужно два нажатия подряд.
     */
    fun rotate(photoId: Long, delta: Int) {
        viewModelScope.launch {
            val cur = _state.value.current ?: return@launch
            val target = cur.photos.firstOrNull { it.id == photoId } ?: return@launch
            val degrees = ((target.rotation + delta) % 360 + 360) % 360
            dao.setRotation(photoId, degrees)

            fun Slide.withRotation(): Slide = when (photoId) {
                photo.id -> copy(photo = photo.copy(rotation = degrees))
                second?.id -> copy(second = second?.copy(rotation = degrees))
                else -> this
            }
            _state.value = _state.value.copy(current = cur.withRotation())
            for (i in history.indices) history[i] = history[i].withRotation()
        }
    }

    /**
     * Скрыть снимок из показа. Файл не трогается. Кадр, в котором он был,
     * уходит из истории, а если он на экране — сразу следующий.
     */
    fun hide(photoId: Long) {
        viewModelScope.launch {
            dao.setHidden(photoId, true)

            val kept = history.filterNot { it.contains(photoId) }
            history.clear()
            history.addAll(kept)
            historyPos = -1

            dropFromQueue(photoId)

            val s = _state.value
            if (s.current?.contains(photoId) == true) {
                _state.value = s.copy(menu = null, inHistory = false)
                advance()
            } else {
                _state.value = s.copy(menu = null)
            }
        }
    }

    /**
     * Серия почти одинаковых кадров: та же папка, полторы минуты по времени
     * съёмки, хэш ближе порога. Показан один — остальные в этом цикле
     * пропускаются (без счётчика показов, чтобы не искажать статистику).
     * Снимки без хэша не трогаются: он считается по мере дообработки.
     */
    private suspend fun skipSeriesOf(photo: Photo) {
        val hash = photo.phash ?: return
        val album = photo.albumName ?: return
        val near = dao.neighboursInTime(
            photo.id, album,
            photo.takenAt - SERIES_WINDOW_MS,
            photo.takenAt + SERIES_WINDOW_MS
        )
        val similar = near.filter { other ->
            val h = other.phash
            h != null && ImageSignals.hamming(hash, h) <= SERIES_MAX_DISTANCE
        }
        if (similar.isNotEmpty()) {
            dao.skipInCycle(similar.map { it.id })
            Log.i(TAG, "Серия: ${photo.displayName} + ещё ${similar.size} похожих, пропущены в этом цикле")
        }
    }

    private fun Slide.contains(photoId: Long): Boolean =
        photo.id == photoId || second?.id == photoId

    // ---------- Погода ----------

    /**
     * Обновление погоды идёт своим циклом, не привязанным к смене кадров:
     * сама служба отдаёт кэш и ходит в сеть не чаще раза в полчаса, а
     * здесь достаточно регулярно её спрашивать.
     */
    private fun startWeather() {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            while (true) {
                val s = application.settingsStore.settings.first()
                if (s.showWeather && (s.weatherLat != 0f || s.weatherLon != 0f)) {
                    val w = tryOrNull {
                        weatherService.current(s.weatherLat.toDouble(), s.weatherLon.toDouble())
                    }
                    _state.value = _state.value.copy(weather = w)
                } else if (_state.value.weather != null) {
                    _state.value = _state.value.copy(weather = null)
                }
                delay(WEATHER_POLL_MS)
            }
        }
    }

    /** Погоду просят обновить сразу — например, после выбора города. */
    fun refreshWeather() = startWeather()

    /**
     * Поиск города для погоды. Тот же Open-Meteo, отдельная служба
     * геокодирования: без ключа и с русскими названиями.
     */
    fun searchCity(query: String) {
        if (query.isBlank()) {
            _cities.value = emptyList()
            return
        }
        viewModelScope.launch {
            _cities.value = tryOrNull { findCities(query) }.orEmpty()
        }
    }

    private suspend fun findCities(query: String): List<City> = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search" +
            "?name=" + java.net.URLEncoder.encode(query.trim(), "UTF-8") +
            "&count=8&language=ru&format=json"

        val body = try {
            application.sources.httpClient()
                .newCall(okhttp3.Request.Builder().url(url).build())
                .execute()
                .use { if (it.isSuccessful) it.body?.string() else null }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            null
        } ?: return@withContext emptyList()

        val results = runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(body)
                as? kotlinx.serialization.json.JsonObject)
                ?.get("results") as? kotlinx.serialization.json.JsonArray
        }.getOrNull() ?: return@withContext emptyList()

        results.mapNotNull { item ->
            val o = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            fun str(key: String) =
                (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val name = str("name") ?: return@mapNotNull null
            val lat = str("latitude")?.toFloatOrNull() ?: return@mapNotNull null
            val lon = str("longitude")?.toFloatOrNull() ?: return@mapNotNull null
            // Область и страна в подписи: «Пушкин» есть и под Петербургом,
            // и в других местах.
            val where = listOfNotNull(str("admin1"), str("country")).joinToString(", ")
            City(name = name, region = where, lat = lat, lon = lon)
        }
    }

    fun loadHidden() {
        viewModelScope.launch { _hidden.value = dao.hiddenPhotos(HIDDEN_LIST_LIMIT) }
    }

    fun unhide(photoId: Long) {
        viewModelScope.launch {
            dao.setHidden(photoId, false)
            _hidden.value = dao.hiddenPhotos(HIDDEN_LIST_LIMIT)
        }
    }

    /**
     * Убрать из хранилища все скрытые снимки разом. Локальные файлы
     * пропускаются: каждый потребовал бы отдельного системного диалога,
     * а это десятки нажатий подряд. Их удаляют по одному с кадра.
     */
    fun deleteHidden(onDone: (String) -> Unit) {
        viewModelScope.launch {
            var done = 0
            var skipped = 0
            val problems = LinkedHashSet<String>()

            for (photo in dao.hiddenPhotos(HIDDEN_LIST_LIMIT)) {
                val source = application.sources.byId(photo.sourceId)
                if (source == null || photo.sourceId == "local") {
                    skipped++
                    continue
                }
                val result = try {
                    source.delete(photo)
                } catch (e: Throwable) {
                    e.rethrowIfCancelled()
                    DeleteResult.Failed(e.message ?: "ошибка")
                }
                when (result) {
                    is DeleteResult.Done -> { dao.deleteById(photo.id); done++ }
                    is DeleteResult.Failed -> problems += result.reason
                    is DeleteResult.Unsupported -> problems += result.reason
                    is DeleteResult.NeedsConfirmation -> skipped++
                }
            }

            _hidden.value = dao.hiddenPhotos(HIDDEN_LIST_LIMIT)

            val report = buildString {
                append("Убрано файлов: $done")
                if (skipped > 0) append("\nПропущено (снимки с устройства удаляются по одному): $skipped")
                problems.take(3).forEach { append("\n").append(it) }
            }
            onDone(report)
        }
    }

    fun unhideAll() {
        viewModelScope.launch {
            dao.unhideAll()
            _hidden.value = emptyList()
        }
    }

    /**
     * Картинка не загрузилась (битый файл, ссылка истекла, кэш вытеснен).
     * Не ждём интервал с чёрным экраном — сразу следующий кадр. Если так
     * несколько раз подряд, показываем сообщение и ждём как обычно: иначе
     * при лежащем хранилище перебор шёл бы без пауз.
     */
    fun reportLoadFailure(photoId: Long) {
        val s = _state.value
        if (s.current?.photo?.id != photoId || s.inHistory) return

        loadFailures++
        if (loadFailures > MAX_LOAD_FAILURES) {
            _state.value = s.copy(
                message = "Снимки не загружаются. Проверьте сеть и хранилище."
            )
            return
        }
        viewModelScope.launch { advance() }
    }

    /** Картинка на экране — цепочка неудач прервана. */
    fun reportLoadSuccess(photoId: Long) {
        if (_state.value.current?.photo?.id == photoId) loadFailures = 0
    }

    /** Кнопка «вправо»: вперёд по истории, а с её края — к новому кадру. */
    fun skip() {
        viewModelScope.launch {
            if (historyPos in 0 until history.size - 1) {
                historyPos++
                showFromHistory()
            } else {
                historyPos = -1
                _state.value = _state.value.copy(inHistory = false)
                advance()
            }
        }
    }

    /**
     * Кнопка «влево»: назад по показанным кадрам.
     *
     * Пока листаем историю, автосмена стоит — иначе снимок, к которому
     * вернулись, тут же уехал бы по таймеру. Показ возобновляется, когда
     * дошли обратно до края или нажали центральную кнопку.
     */
    fun back() {
        viewModelScope.launch {
            if (history.isEmpty()) return@launch

            val pos = if (historyPos < 0) history.size - 1 else historyPos
            if (pos <= 0) return@launch

            historyPos = pos - 1
            showFromHistory()
        }
    }

    private fun showFromHistory() {
        val slide = history.getOrNull(historyPos) ?: return
        _state.value = _state.value.copy(
            current = slide,
            message = null,
            inHistory = true
        )
    }

    private suspend fun advance() = advanceLock.withLock {
        // Отсчёт с начала попытки: даже неудачная смена не должна
        // превращать цикл в плотный перебор.
        lastAdvanceAt = System.currentTimeMillis()

        val settings = _state.value.settings
        val currentId = _state.value.current?.photo?.id

        // Кадр, подготовленный заранее. Если из-за гонки быстрых нажатий
        // он совпал с текущим — готовим другой.
        // Кадр, который успел попасть в пару и уже показан, пропускаем:
        // партнёр в пару берётся из ещё не показанных, а очередь этого
        // не знает.
        val recent = history.takeLast(6).flatMap { it.photos.map { p -> p.id } }.toSet()
        var slide: Slide? = null
        while (queue.isNotEmpty()) {
            val head = queue.removeFirst()
            val ids = head.photos.map { it.id }
            if (head.photo.id != currentId && ids.none { it in recent }) { slide = head; break }
        }

        if (slide == null) slide = buildSlideWithRetry(settings)

        // Переход начинается только когда картинка уже в памяти: иначе
        // анимация уходила в тёмное, а новый кадр появлялся, когда его
        // успевали раскодировать. Для кадра из очереди это мгновенно —
        // он уже прогрет; для собранного на месте — ждём, но недолго.
        // Самый первый кадр не ждёт: перехода ещё нет, защищать нечего,
        // а человек смотрит на пустой экран.
        if (slide != null && currentId != null) warmToMemory(slide)

        if (slide == null) {
            // Причина — точная: «нет фотографий» пишется только когда их
            // действительно нет. Отсеянные фильтром и отключённые источники
            // — другая история, и человек должен понимать, куда смотреть.
            val total = dao.countAll()
            val enabled = dao.countEnabled()
            val msg = when {
                total == 0 ->
                    "Нет проиндексированных фотографий. Откройте настройки и выберите источник."
                enabled == 0 ->
                    "В индексе $total снимков, но все отсеяны фильтром содержимого или " +
                        "относятся к отключённым источникам. Проверьте настройки."
                else ->
                    "Не удалось получить снимок из источника. Проверьте сеть и хранилище."
            }
            _state.value = _state.value.copy(message = msg)
            return@withLock
        }

        // Пометка ставится в момент фактического показа, а не при подготовке:
        // иначе пропущенные кадры выпадали бы из круга, а пауза повторов
        // отсчитывалась бы не от того времени. У пары отмечаются оба снимка.
        val shownAt = System.currentTimeMillis()
        slide.photos.forEach { dao.markShown(it.id, shownAt) }
        if (_state.value.settings.skipSimilar) {
            slide.photos.forEach { skipSeriesOf(it) }
        }

        history.addLast(slide)
        while (history.size > HISTORY_LIMIT) history.removeFirst()
        historyPos = -1

        _state.value = _state.value.copy(
            current = slide,
            message = null,
            inHistory = false,
            zoom = 1f,
            panX = 0f,
            panY = 0f,
            menu = null
        )
        lastAdvanceAt = System.currentTimeMillis()

        // Готовим следующий кадр заранее: к моменту смены и ссылка получена,
        // и сам файл уже лежит в кэше — сеть показ не задерживает.
        ensureQueue()
    }

    /**
     * Наполнение очереди в фоне, по одному кадру за раз. Второй запуск
     * при уже идущем — ничего не делает.
     */
    private fun ensureQueue() {
        if (fillJob?.isActive == true) return
        fillJob = viewModelScope.launch {
            while (queue.size < PREFETCH_DEPTH) {
                // Наполнение постепенное. Первый запасной кадр нужен срочно —
                // он и есть следующий показ. Остальные могут подождать:
                // скачать и разобрать пять файлов подряд сразу после запуска
                // значит занять сеть и процессор ровно в те секунды, когда
                // идёт первый показ и первый наезд.
                if (queue.isNotEmpty()) {
                    delay(if (queue.size < MEMORY_AHEAD) FILL_GAP_SOON_MS else FILL_GAP_LATER_MS)
                }

                val settings = _state.value.settings
                val busy = queue.flatMap { it.photos.map { p -> p.id } }.toSet() +
                    listOfNotNull(_state.value.current?.photo?.id)
                val slide = buildSlide(settings, exclude = busy) ?: break
                queue.addLast(slide)
                // Ближайшие — сразу в память, остальные лежат на диске.
                if (queue.indexOf(slide) < MEMORY_AHEAD) warmToMemory(slide)
            }
        }
    }

    private fun clearQueue() {
        fillJob?.cancel()
        queue.clear()
        if (loop?.isActive == true) ensureQueue()
    }

    private fun dropFromQueue(photoId: Long) {
        queue.removeAll { it.contains(photoId) }
    }

    /**
     * Раскодировать кадр в память в размере экрана и дождаться. Именно
     * размер экрана: AsyncImage запросит его же и попадёт в кэш памяти,
     * а не пойдёт раскодировать заново. Прежний прогрев без размера
     * складывал в кэш оригинал на десятки мегабайт — он и не помещался
     * толком, и вытеснял соседей.
     */
    private suspend fun warmToMemory(slide: Slide) {
        val dm = application.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1)
        val h = dm.heightPixels.coerceAtLeast(1)
        withTimeoutOrNull(WARM_TIMEOUT_MS) {
            listOfNotNull(slide.displayUrl, slide.secondUrl).forEach { url ->
                tryOrNull {
                    application.imageLoader.execute(
                        ImageRequest.Builder(application).data(url).size(w, h).build()
                    )
                }
            }
        }
    }

    /**
     * Несколько попыток подряд: если у конкретного снимка не получилась
     * ссылка (сеть моргнула, файл пропал), случайный выбор почти наверняка
     * предложит другой. Это заменяет чёрный кадр на следующий рабочий.
     */
    private suspend fun buildSlideWithRetry(settings: SlideshowSettings): Slide? {
        repeat(4) {
            buildSlide(settings)?.let { return it }
            if (dao.countEnabled() == 0) return null
        }
        return null
    }

    private suspend fun buildSlide(
        settings: SlideshowSettings,
        exclude: Set<Long> = emptySet()
    ): Slide? {
        // Выборка случайная и про очередь не знает: тот же снимок может
        // выпасть дважды, пока первый ещё не показан. Несколько попыток
        // это исправляют почти всегда.
        var picked: Photo? = null
        for (attempt in 0 until 6) {
            val p = picker.next(settings) ?: return null
            if (p.id !in exclude) { picked = p; break }
        }
        val chosen = picked ?: picker.next(settings) ?: return null
        val source = application.sources.byId(chosen.sourceId) ?: return null
        val url = tryOrNull { source.resolveDisplayUrl(chosen) } ?: return null

        // Подписи для этого кадра считаются здесь же, а не ждут очереди
        // сплошного прохода: на архиве в десятки тысяч снимков очередь
        // добирается до случайно выбранного кадра практически никогда.
        // Кадр готовится заранее, пока показывается предыдущий, так что
        // время на чтение заголовка есть; ограничение — чтобы медленное
        // хранилище не задержало смену.
        val photo = withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
            tryOrNull { enricher.enrichNow(chosen, url) }
        } ?: chosen

        if (!settings.pairPortraits) return Slide(photo, url)

        if (!isPortrait(photo)) {
            Log.i(
                TAG,
                "Кадр ${photo.displayName} (${photo.sourceId}): одиночный, " +
                    "размеры ${photo.width}×${photo.height}, metaDone=${photo.metaDone}, попыток ${photo.metaTries}"
            )
            return Slide(photo, url)
        }

        val pair = findPartner(photo, settings)
        if (pair == null) {
            Log.i(
                TAG,
                "Кадр ${photo.displayName}: вертикальный, партнёр не найден " +
                    "(вертикальных с размерами в индексе: ${dao.countPortraits()})"
            )
            return Slide(photo, url)
        }
        Log.i(TAG, "Кадр ${photo.displayName}: пара с ${pair.first.displayName} (${pair.first.sourceId})")
        return Slide(photo, url, pair.first, pair.second)
    }

    /**
     * Ориентация известна только по размерам из EXIF. Пока они не
     * прочитаны, снимок в пару не берётся: ошибиться здесь хуже, чем
     * показать его одиночным кадром.
     */
    private fun isPortrait(photo: Photo): Boolean =
        photo.width > 0 && photo.height > photo.width

    /**
     * Второй вертикальный снимок и ссылка на него.
     *
     * Сначала пробуем ту же папку — пара из одной поездки смотрится
     * осмысленнее случайного соседства, — потом любую.
     */
    private suspend fun findPartner(
        photo: Photo,
        settings: SlideshowSettings
    ): Pair<Photo, String>? {
        val cooldown = if (settings.repeatCooldownDays > 0) {
            System.currentTimeMillis() - settings.repeatCooldownDays * DAY_MS
        } else 0L

        // Пара по содержимому: к снимку с людьми — снимок с людьми, к
        // снимку без людей — без. Требование мягкое: если подходящего
        // не нашлось, берём любой, иначе на архиве, где лица ещё не
        // досчитаны, пары просто перестали бы складываться.
        //
        // faceCount = -1 значит «лица ещё не искали». Такой снимок ни к
        // одной из групп не относится, и ограничение к нему не
        // применяется — подбирается как раньше.
        val byContent = settings.pairByContent && photo.faceCount >= 0
        val people = if (byContent && photo.faceCount > 0) 1 else 0
        val noPeople = if (byContent && photo.faceCount == 0) 1 else 0

        val candidate = dao.randomPortrait(photo.id, photo.albumName, cooldown, people, noPeople)
            ?: dao.randomPortrait(photo.id, null, cooldown, people, noPeople)
            ?: dao.randomPortrait(photo.id, null, 0L, people, noPeople)
            ?: dao.leastRecentPortraitLike(photo.id, people, noPeople)
            // Подходящих по содержимому не осталось — лучше пара
            // «человек с салатом», чем одинокий кадр с широкими полями.
            ?: dao.randomPortrait(photo.id, null, cooldown)
            ?: dao.randomPortrait(photo.id, null, 0L)
            ?: dao.leastRecentPortrait(photo.id)
            ?: return null

        // Партнёр — не дубль первого кадра: два одинаковых снимка рядом
        // выглядят как ошибка. Проверка дешёвая, но работает только когда
        // у обоих посчитан хэш.
        val h1 = photo.phash
        val h2 = candidate.phash
        if (h1 != null && h2 != null && ImageSignals.hamming(h1, h2) <= SERIES_MAX_DISTANCE) {
            Log.i(TAG, "Пара: ${candidate.displayName} слишком похож на ${photo.displayName}, ищем другого")
            dao.skipInCycle(listOf(candidate.id))
            return null
        }

        val source = application.sources.byId(candidate.sourceId) ?: return null
        val url = tryOrNull { source.resolveDisplayUrl(candidate) } ?: return null

        val enriched = withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
            tryOrNull { enricher.enrichNow(candidate, url) }
        } ?: candidate

        return enriched to url
    }

    /** Сколько записей от источника в индексе — для кнопки удаления. */
    private val _sourceCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val sourceCounts: StateFlow<Map<String, Int>> = _sourceCounts.asStateFlow()

    fun loadSourceCounts() {
        viewModelScope.launch {
            _sourceCounts.value = listOf("local", "yandex", "smb").associateWith { dao.countAllBySource(it) }
        }
    }

    /**
     * Убрать из индекса всё от источника. Для случая, когда источник
     * отключён насовсем: держать его записи незачем, они только
     * раздувают счётчики.
     */
    fun removeSourcePhotos(sourceId: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val n = dao.countAllBySource(sourceId)
            dao.deleteBySource(sourceId)

            val kept = history.filterNot { it.photos.any { p -> p.sourceId == sourceId } }
            history.clear()
            history.addAll(kept)
            historyPos = -1
            clearQueue()

            val cur = _state.value.current
            if (cur != null && cur.photos.any { it.sourceId == sourceId }) {
                _state.value = _state.value.copy(inHistory = false)
                advance()
            }
            loadSourceCounts()
            onDone("Удалено из индекса: $n")
        }
    }

    /**
     * Пересчёт фильтра. Вызывается после каждой правки настроек содержимого —
     * переиндексация при этом не нужна, потому что фильтр только переставляет
     * флаг допуска у уже собранных записей.
     */
    fun applyFilter(onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            val s = application.settingsStore.settings.first()
            val r = filter.apply(s)
            onDone("К показу допущено ${r.enabled}, отсеяно ${r.hidden}")
        }
    }

    // ---------- Обзор папок ----------

    /**
     * Открывает обзор для источника. Корень у каждого свой: у сетевой папки
     * это верх шары, у Яндекс.Диска — disk:/.
     */
    fun openBrowser(sourceId: String, title: String) {
        val root = when (sourceId) {
            "yandex" -> "disk:/"
            // Сетевая папка начинается со списка общих папок хранилища.
            "smb" -> SHARES_ROOT
            else -> ""
        }
        _browse.value = BrowseState(
            visible = true,
            sourceId = sourceId,
            title = title,
            path = root,
            stack = emptyList(),
            loading = true
        )
        loadFolders(root)
    }

    fun browseInto(folder: Folder) {
        val current = _browse.value

        // Выбор общей папки: сохраняем её в настройки и заходим в корень.
        if (folder.path.startsWith(SHARE_PREFIX)) {
            val shareName = folder.path.removePrefix(SHARE_PREFIX)
            viewModelScope.launch {
                val s = application.settingsStore.settings.first()
                application.settingsStore.setSmb(s.smbHost, shareName, s.smbUser, s.smbPassword, "")
                _browse.value = _browse.value.copy(
                    path = "",
                    stack = current.stack + current.path,
                    loading = true,
                    error = null
                )
                loadFolders("")
            }
            return
        }

        _browse.value = current.copy(
            path = folder.path,
            stack = current.stack + current.path,
            loading = true,
            error = null
        )
        loadFolders(folder.path)
    }

    /** Возврат на уровень выше. Со дна стека закрывает обзор. */
    fun browseUp() {
        val current = _browse.value
        if (current.stack.isEmpty()) {
            closeBrowser()
            return
        }
        val parent = current.stack.last()
        _browse.value = current.copy(
            path = parent,
            stack = current.stack.dropLast(1),
            loading = true,
            error = null
        )
        loadFolders(parent)
    }

    fun closeBrowser() {
        _browse.value = BrowseState()
    }

    /** Текущий путь выбран пользователем. null — выбирать здесь нечего (список шар). */
    fun chosenPath(): String? = _browse.value.path.takeIf { it != SHARES_ROOT }

    private fun loadFolders(path: String) {
        viewModelScope.launch {
            val state = _browse.value
            val source = application.sources.byId(state.sourceId)

            if (source == null) {
                _browse.value = state.copy(loading = false, error = "Источник недоступен")
                return@launch
            }

            // Верхний уровень сетевого хранилища — его общие папки. Шара
            // ещё не выбрана, поэтому обычная проверка готовности здесь
            // не подходит.
            if (state.sourceId == "smb" && path == SHARES_ROOT) {
                val smb = application.sources.smb
                val shares = smb.listShares()
                _browse.value = _browse.value.copy(
                    loading = false,
                    entries = shares.map { Folder(path = SHARE_PREFIX + it, name = it) },
                    error = if (shares.isEmpty()) {
                        "Не удалось получить список общих папок. Проверьте адрес, " +
                            "имя пользователя и пароль." +
                            (smb.lastError?.let { "\n\n" + it } ?: "")
                    } else {
                        null
                    }
                )
                return@launch
            }

            if (tryOrNull { source.isReady() } != true) {
                // Показываем настоящую причину, а не общую фразу: без этого
                // неверный пароль, отсутствующая шара и несогласованный
                // протокол выглядят на экране одинаково — и, что хуже,
                // сообщение про NAS уходило и для Яндекс.Диска.
                val detail = when (source) {
                    is com.fotoframe.source.SmbSource -> source.lastError
                    is com.fotoframe.source.yandex.YandexDiskSource -> source.lastError
                    else -> null
                }
                val head = when (state.sourceId) {
                    "yandex" -> "Не удалось подключиться к Яндекс.Диску."
                    "smb" -> "Не удалось подключиться. Проверьте адрес, общую папку, имя пользователя и пароль."
                    else -> "Не удалось подключиться."
                }
                _browse.value = state.copy(
                    loading = false,
                    error = head + (detail?.let { "\n\n" + it } ?: "")
                )
                return@launch
            }

            val folders = runCatching { source.listFolders(path.ifBlank { null }) }
            _browse.value = _browse.value.copy(
                loading = false,
                entries = folders.getOrDefault(emptyList()),
                error = folders.exceptionOrNull()?.let { "Ошибка чтения: ${it.message}" }
            )
        }
    }

    // ---------- Обновление индекса ----------

    /**
     * Обновление при запуске — только если с прошлого прошло достаточно
     * времени. Заставка открывает это окно по несколько раз в день, и
     * полный обход трёх источников при каждом запуске мешал показу.
     */
    fun reindexIfStale(maxAgeMs: Long, onDone: (String) -> Unit = {}) {
        viewModelScope.launch {
            val last = application.settingsStore.lastIndexAt()
            if (System.currentTimeMillis() - last >= maxAgeMs) reindex(onDone = onDone)
        }
    }

    /**
     * Ручное обновление индекса с экрана настроек.
     *
     * @param force удалять пропавшие записи, даже если их подозрительно
     *   много. Для случая, когда пользователь сам переложил архив.
     */
    fun reindex(force: Boolean = false, onDone: (String) -> Unit = {}) {
        if (indexing) {
            onDone("Обновление уже идёт")
            return
        }
        indexing = true

        viewModelScope.launch {
            val report = StringBuilder()
            try {
                val settings = application.settingsStore.settings.first()

                report.append("Устройство: ${indexer.index(application.sources.local, "*", force).describe()}")

                if (settings.yandexToken != null) {
                    report.append("\nЯндекс.Диск: ${indexer.index(application.sources.yandex, settings.yandexFolder, force).describe()}")
                } else {
                    report.append("\nЯндекс.Диск: токен не задан")
                }

                if (settings.smbHost.isNotBlank() && settings.smbShare.isNotBlank()) {
                    val r = indexer.index(application.sources.smb, settings.smbFolder, force)
                    val detail = application.sources.smb.lastError
                    report.append("\nСетевая папка: " + if (r.ok) r.describe() else (detail ?: r.error))
                }

                val s = application.settingsStore.settings.first()
                val f = filter.apply(s)
                report.append("\nК показу допущено ${f.enabled}, отсеяно ${f.hidden}")
                application.settingsStore.setLastIndexAt(System.currentTimeMillis())
                onDone(report.toString().trim())

                // Ручное обновление — повод дать ещё попытки тому, что не
                // удалось прочитать: возможно, хранилище снова в сети.
                dao.resetTries()
            } finally {
                indexing = false
            }

            // Дообработка идёт фоном и может занять часы, показ её не ждёт.
            // Порядок важен: сперва дешёвый разбор EXIF по всей коллекции
            // (даты и координаты), затем города, и только потом поиск лиц.
            tryOrNull {
                enricher.enrichAll {
                    onDone(report.toString().trim() + "\n" + placesReport())
                }
            }
        }
    }

    /**
     * Что происходит с коллекцией и с кадром на экране. Показывается при
     * открытии настроек: по ней видно, идёт ли дообработка, застряла ли,
     * и почему у конкретного снимка нет подписи.
     */
    fun showDiagnostics(onDone: (String) -> Unit) {
        viewModelScope.launch { onDone(diagnostics()) }
    }

    private suspend fun diagnostics(): String {
        val max = PhotoEnricher.MAX_TRIES
        val total = dao.countEnabled()
        val lines = ArrayList<String>()

        lines += "Коллекция: $total к показу " +
            "(устройство ${dao.countBySource("local")}, " +
            "Яндекс ${dao.countBySource("yandex")}, " +
            "сеть ${dao.countBySource("smb")})"
        lines += "EXIF прочитан: ${dao.countMetaDone()}, " +
            "в очереди: ${dao.countNeedingMeta(max)}, " +
            "не читается: ${dao.countMetaStuck(max)}"
        lines += "Настоящая дата съёмки: ${dao.countExactDate()}"
        lines += "С координатами: ${dao.countWithCoords()}, " +
            "с местом: ${dao.countWithPlace()}, " +
            "ждут геокодер: ${dao.countPlacePending()}, " +
            "места нет: ${dao.countPlaceEmpty()}"
        lines += if (indexing) "Обновление списка идёт" else "Обновление списка не идёт"

        val shownId = _state.value.current?.photo?.id
        val p = shownId?.let { dao.byId(it) }
        if (p != null) {
            val date = when {
                p.takenAtExact -> "настоящая"
                p.metaDone -> "в EXIF нет — показана не будет"
                p.metaTries >= max -> "EXIF не читается (попыток ${p.metaTries})"
                else -> "EXIF ещё не читали (попыток ${p.metaTries})"
            }
            val placeName = p.placeName
            val place = when {
                p.latitude == null -> "координат нет"
                placeName == null -> "координаты есть, места ещё нет (попыток ${p.placeTries})"
                placeName.isEmpty() -> "координаты есть, геокодер не нашёл названия"
                else -> placeName
            }
            lines += "Текущий кадр: ${p.sourceId}, ${p.displayName}"
            lines += "  дата: $date; место: $place"
        }

        return lines.joinToString("\n")
    }

    /** Короткая сводка по подписям — чтобы видеть, что дообработка идёт. */
    private suspend fun placesReport(): String {
        val places = dao.countWithPlace()
        val pending = dao.countNeedingMeta(PhotoEnricher.MAX_TRIES)
        return "Снимков с местом съёмки: $places" +
            if (pending > 0) ", ещё разбирается: $pending" else ""
    }

    companion object {
        private const val TAG = "Slideshow"

        /** Виртуальный корень обзора сетевого хранилища: список общих папок. */
        const val SHARES_ROOT = "\u0000shares"

        /** Путь элемента в этом списке: префикс + имя общей папки. */
        const val SHARE_PREFIX = "\u0000share:"

        private const val HISTORY_LIMIT = 20

        /** Потолок на разбор кадра перед показом: заголовок, место, лица. */
        private const val ENRICH_TIMEOUT_MS = 15_000L

        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** Ступени увеличения. Первая — обычный показ. */
        private val ZOOM_STEPS = listOf(1f, 1.5f, 2f, 3f, 4f)

        /** После стольких нечитаемых кадров подряд — сообщение и обычная пауза. */
        private const val MAX_LOAD_FAILURES = 5

        /** Сколько скрытых показывать в настройках. */
        private const val HIDDEN_LIST_LIMIT = 100

        /** Через сколько убрать сообщение об удалении. */
        private const val MESSAGE_MS = 6_000L

        /** Повтор попытки, пока на экране пусто. */
        private const val EMPTY_RETRY_MS = 5_000L

        /** Сколько кадров держать готовыми впереди. */
        private const val PREFETCH_DEPTH = 5

        /** Сколько из них — раскодированными в памяти. */
        private const val MEMORY_AHEAD = 2

        /** Пауза перед вторым запасным кадром — он ещё близко. */
        private const val FILL_GAP_SOON_MS = 3_000L

        /** Пауза перед остальными — они понадобятся через минуту-две. */
        private const val FILL_GAP_LATER_MS = 8_000L

        /** Потолок ожидания раскодировки перед показом. */
        private const val WARM_TIMEOUT_MS = 5_000L

        /** Окно серии по времени съёмки — в обе стороны. */
        private const val SERIES_WINDOW_MS = 90_000L

        /** Хэши ближе этого — почти одинаковые кадры (из 64 бит). */
        private const val SERIES_MAX_DISTANCE = 10

        /** Как часто спрашивать погоду. Сеть при этом трогается раз в полчаса. */
        private const val WEATHER_POLL_MS = 5L * 60 * 1000


    }
}
