package com.fotoframe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.fotoframe.engine.SlideshowViewModel
import com.fotoframe.source.LocalGallerySource
import com.fotoframe.ui.FolderBrowser
import com.fotoframe.ui.SettingsScreen
import com.fotoframe.ui.SlideshowScreen
import com.fotoframe.ui.theme.FotoFrameTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: SlideshowViewModel by viewModels()

    // Состояние экрана держим в активити, а не внутри composable:
    // обработчик кнопок пульта живёт вне композиции и должен уметь
    // открывать настройки.
    private var showSettings by mutableStateOf(false)
    private var status by mutableStateOf<String?>(null)

    /**
     * Системный диалог удаления локального файла. С Android 11 стереть
     * чужой снимок можно только так: MediaStore выдаёт PendingIntent,
     * пользователь подтверждает, и результат приходит сюда.
     */
    private var deletingPhotoId: Long? = null

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val id = deletingPhotoId
        deletingPhotoId = null
        if (id != null) vm.onDeleteConfirmed(id, result.resultCode == RESULT_OK)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Смотрим на фактическое состояние, а не на ответ диалога: на
        // Android 14 «выбранные фото» приходят другим разрешением.
        if (LocalGallerySource.hasMediaPermission(this)) {
            // Разрешение только что выдано — локальную коллекцию ещё не
            // видели, здесь обход нужен без оглядки на давность.
            vm.reindex { status = it }
        } else {
            // Отказ — не повод оставлять Яндекс.Диск и сетевую папку без
            // обновления: раньше без доступа к памяти рамка вообще не
            // обходила источники при запуске.
            vm.reindexIfStale(STARTUP_REINDEX_AFTER_MS) { status = it }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Окно подстраивается под клавиатуру, иначе поля ввода в настройках
        // остаются под ней.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        applyImmersive(true)

        val store = (application as App).settingsStore

        setContent {
            FotoFrameTheme {
                val state by vm.state.collectAsState()

                val hidden by vm.hiddenPhotos.collectAsState()
                val cities by vm.cities.collectAsState()

                // Запрос системного подтверждения приходит из ViewModel:
                // там нет Activity, а показать диалог может только она.
                LaunchedEffect(state.pendingDelete) {
                    val pending = state.pendingDelete
                    if (pending != null && deletingPhotoId == null) {
                        deletingPhotoId = pending.photoId
                        deleteLauncher.launch(
                            IntentSenderRequest.Builder(pending.request.intentSender).build()
                        )
                    }
                }

                LaunchedEffect(showSettings) {
                    applyImmersive(!showSettings)
                    if (showSettings) vm.loadHidden()
                    // При входе в настройки — сводка по коллекции и текущему кадру.
                    if (showSettings) vm.showDiagnostics { status = it }
                }

                // Настройка «не гасить экран» теперь действует, а не только
                // хранится.
                LaunchedEffect(state.settings.keepScreenOn) {
                    if (state.settings.keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                val browseState by vm.browse.collectAsState()

                if (browseState.visible) {
                    FolderBrowser(
                        state = browseState,
                        onOpen = { vm.browseInto(it) },
                        onUp = { vm.browseUp() },
                        onChoose = {
                            val chosen = vm.chosenPath()
                            if (chosen == null) {
                                status = "Сначала откройте одну из общих папок."
                                return@FolderBrowser
                            }
                            lifecycleScope.launch {
                                when (browseState.sourceId) {
                                    "yandex" -> store.setYandexFolder(chosen)
                                    "smb" -> store.setSmbFolder(chosen)
                                }
                                status = "Папка выбрана. Запустите обновление списка."
                            }
                            vm.closeBrowser()
                        },
                        onCancel = { vm.closeBrowser() }
                    )
                } else if (showSettings) {
                    SettingsScreen(
                        settings = state.settings,
                        totalPhotos = state.totalPhotos,
                        statusMessage = status,
                        onIntervalChange = { lifecycleScope.launch { store.setInterval(it) } },
                        onOrderChange = { lifecycleScope.launch { store.setOrder(it) } },
                        onTransitionChange = { lifecycleScope.launch { store.setTransition(it) } },
                        onTransitionMillisChange = {
                            lifecycleScope.launch { store.setTransitionMillis(it) }
                        },
                        onFitModeChange = { lifecycleScope.launch { store.setFitMode(it) } },
                        onFaceFocusChange = { lifecycleScope.launch { store.setFaceFocus(it) } },
                        onFitFaceZoomChange = { lifecycleScope.launch { store.setFitFaceZoom(it) } },
                        onZoomStrengthChange = { lifecycleScope.launch { store.setZoomStrength(it) } },
                        onZoomPaceChange = { lifecycleScope.launch { store.setZoomPace(it) } },
                        onZoomWithoutFaceChange = { lifecycleScope.launch { store.setZoomWithoutFace(it) } },
                        onPairByContentChange = { lifecycleScope.launch { store.setPairByContent(it) } },
                        onShowWeatherChange = {
                            lifecycleScope.launch {
                                store.setShowWeather(it)
                                vm.refreshWeather()
                            }
                        },
                        cities = cities,
                        onCitySearch = { vm.searchCity(it) },
                        onCityPick = { city ->
                            lifecycleScope.launch {
                                store.setWeatherPlace(city.name, city.lat, city.lon)
                                vm.refreshWeather()
                                status = "Город для погоды: ${city.name}"
                            }
                        },
                        onPortraitFitWholeChange = {
                            lifecycleScope.launch { store.setPortraitFitWhole(it) }
                        },
                        onPairPortraitsChange = {
                            lifecycleScope.launch { store.setPairPortraits(it) }
                        },
                        onRecencyBiasChange = { lifecycleScope.launch { store.setRecencyBias(it) } },
                        onFreshBoostChange = { lifecycleScope.launch { store.setFreshBoost(it) } },
                        onFreshWindowChange = {
                            lifecycleScope.launch { store.setFreshWindowDays(it) }
                        },
                        onRepeatCooldownChange = {
                            lifecycleScope.launch { store.setRepeatCooldownDays(it) }
                        },
                        onMinMegapixelsChange = {
                            lifecycleScope.launch {
                                store.setMinMegapixels(it)
                                vm.applyFilter { r -> status = r }
                            }
                        },
                        onMinFileSizeChange = {
                            lifecycleScope.launch {
                                store.setMinFileSizeKb(it)
                                vm.applyFilter { r -> status = r }
                            }
                        },
                        onSkipJunkFoldersChange = {
                            lifecycleScope.launch {
                                store.setSkipJunkFolders(it)
                                vm.applyFilter { r -> status = r }
                            }
                        },
                        onSkipPngChange = {
                            lifecycleScope.launch {
                                store.setSkipPng(it)
                                vm.applyFilter { r -> status = r }
                            }
                        },
                        onMaxAspectChange = {
                            lifecycleScope.launch {
                                store.setMaxAspectRatio(it)
                                vm.applyFilter { r -> status = r }
                            }
                        },
                        onShowClockChange = { lifecycleScope.launch { store.setShowClock(it) } },
                        onShowDateChange = { lifecycleScope.launch { store.setShowDate(it) } },
                        onShowPhotoDateChange = {
                            lifecycleScope.launch { store.setShowPhotoDate(it) }
                        },
                        onShowLocationChange = {
                            lifecycleScope.launch { store.setShowLocation(it) }
                        },
                        onKeepScreenOnChange = {
                            lifecycleScope.launch { store.setKeepScreenOn(it) }
                        },
                        onYandexTokenChange = { token ->
                            lifecycleScope.launch {
                                store.setYandexToken(token)
                                status = "Токен сохранён. Запустите обновление списка."
                            }
                        },
                        onSmbChange = { host, share, user, password, folder ->
                            lifecycleScope.launch {
                                store.setSmb(host, share, user, password, folder)
                                status = "Настройки сохранены. Запустите обновление списка."
                            }
                        },
                        onSmbConnect = { host, share, user, password ->
                            lifecycleScope.launch {
                                // Сначала запись в хранилище настроек целиком,
                                // и только потом подключение — иначе обзор
                                // папок читал ещё старые (пустые) данные.
                                store.setSmb(host, share, user, password, state.settings.smbFolder)
                                vm.openBrowser("smb", "Папки на сетевом хранилище")
                            }
                        },
                        onYandexConnect = { token ->
                            lifecycleScope.launch {
                                store.setYandexToken(token)
                                vm.openBrowser("yandex", "Папки на Яндекс.Диске")
                            }
                        },
                        onReindex = {
                            status = "Идёт обновление…"
                            vm.reindex { report -> status = report }
                        },
                        onReindexForce = {
                            status = "Идёт обновление с удалением пропавших…"
                            vm.reindex(force = true) { report -> status = report }
                        },
                        hiddenPhotos = hidden,
                        onUnhide = { vm.unhide(it) },
                        onUnhideAll = { vm.unhideAll() },
                        onDeleteHidden = {
                            status = "Убираю скрытые файлы из хранилища…"
                            vm.deleteHidden { report -> status = report }
                        },
                        onDiagnostics = { vm.showDiagnostics { status = it } },
                        onClose = { showSettings = false }
                    )
                } else {
                    SlideshowScreen(
                        state = state,
                        // На пульте меню действий висит на кнопке «Меню»,
                        // а на планшете такой кнопки нет — там его открывает
                        // долгое нажатие. Настройки внутри меню отдельным
                        // пунктом, так что путь к ним не теряется.
                        onLongPress = { openActionMenu(armed = true) },
                        onMenuPick = { index ->
                            val item = vm.menuPick(index)
                            if (item?.openSettings == true) showSettings = true
                        },
                        onMenuClose = { vm.closeMenu() },
                        onTap = { if (state.zoomed) vm.resetZoom() else vm.togglePause() },
                        onSwipeNext = { if (!state.zoomed) vm.skip() },
                        onSwipePrev = { if (!state.zoomed) vm.back() },
                        onPinchIn = { vm.zoomIn() },
                        onPinchOut = { vm.zoomOut() },
                        onLoadError = { vm.reportLoadFailure(it) },
                        onLoadSuccess = { vm.reportLoadSuccess(it) }
                    )
                }
            }
        }

        requestMediaPermission()
        vm.start()
    }

    /**
     * Полноэкранный режим включён только на показе. В настройках панели
     * возвращаются: иначе экранная клавиатура на части прошивок не
     * поднимается или прячется вместе с ними.
     */
    private fun applyImmersive(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun requestMediaPermission() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                }
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            // Без этого разрешения система вырезает GPS из EXIF, и подпись
            // города для локальных снимков сделать не из чего.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }

        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        // Доступ к галерее уже есть (пусть и частичный) — диалог не нужен,
        // даже если какое-то из разрешений формально не выдано.
        val haveMedia = LocalGallerySource.hasMediaPermission(this) &&
            missing.none { it == Manifest.permission.ACCESS_MEDIA_LOCATION }

        if (missing.isEmpty() || haveMedia) {
            // При каждом запуске обходить источники незачем: заставка
            // открывает окно по несколько раз в день. Раз в час достаточно,
            // остальное делает фоновый воркер и кнопка в настройках.
            vm.reindexIfStale(STARTUP_REINDEX_AFTER_MS) { status = it }
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * Управление пультом.
     *
     * Обычный показ: OK — пауза, «Меню» (или долгое OK) — меню действий
     * над кадром, вправо — следующий, влево — предыдущий, вверх —
     * увеличить, вниз — настройки, «назад» — выход. Каналы ± и Zoom ± (есть не
     * на всех пультах) — тоже увеличение.
     *
     * Пока кадр увеличен, стрелки двигают его, каналы ± меняют масштаб,
     * OK и «назад» возвращают обычный размер. Меню открывает настройки
     * как обычно.
     *
     * В меню действий: вверх/вниз — выбор, OK — выполнить, «назад» —
     * закрыть.
     *
     * OK обрабатывается на отпускании, а не на нажатии: иначе нельзя
     * отличить короткое нажатие от долгого. На нажатии включается
     * отслеживание, долгое приходит в [onKeyLongPress], а короткое — в
     * [onKeyUp] с флагом isTracking и без isCanceled.
     */
    private fun isOk(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER

    private val slideshowVisible: Boolean
        get() = !showSettings && !vm.browse.value.visible

    /**
     * Меню принимает OK только после того, как кнопку отпустили, и не
     * раньше чем через [MENU_GRACE_MS] после открытия. Пульты бывают
     * разные: одни при удержании шлют повторы, другие — серию отдельных
     * нажатий с отпусканиями между ними. И то и другое без этих двух
     * проверок выбирало первый пункт сразу после открытия.
     */
    private var menuArmed = false
    private var menuOpenedAt = 0L

    /**
     * Было ли нажатие OK начато на самом показе.
     *
     * OK обрабатывается на отпускании — иначе не отличить короткое
     * нажатие от долгого. Но кнопку «Вернуться к показу» в настройках
     * тоже нажимают этим OK: настройки закрываются по нажатию, а
     * отпускание прилетает уже в показ и ставит паузу. Со стороны это
     * выглядит как зависшая рамка, потому что индикатора паузы не было.
     */
    private var okPressedHere = false

    private fun openActionMenu(armed: Boolean) {
        menuArmed = armed
        menuOpenedAt = android.os.SystemClock.uptimeMillis()
        vm.openMenu()
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (slideshowVisible && isOk(keyCode) && vm.state.value.menu == null) {
            openActionMenu(armed = false)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (slideshowVisible && isOk(keyCode) && event != null) {
            val startedHere = okPressedHere
            okPressedHere = false

            if (vm.state.value.menu != null) {
                // Кнопку отпустили — теперь меню можно подтверждать.
                menuArmed = true
            } else if (startedHere && event.isTracking && !event.isCanceled) {
                if (vm.state.value.zoomed) vm.resetZoom() else vm.togglePause()
            }
            return true
        }
        okPressedHere = false
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Обзор папок поверх настроек: «назад» ведёт на уровень выше, а с
        // корня закрывает обзор. Раньше он снимал флаг настроек, экран не
        // менялся, а второе нажатие завершало приложение.
        if (vm.browse.value.visible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                vm.browseUp()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        if (showSettings) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                showSettings = false
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        val current = vm.state.value

        if (current.menu != null) {
            val repeat = (event?.repeatCount ?: 0) > 0
            val settled = android.os.SystemClock.uptimeMillis() - menuOpenedAt >= MENU_GRACE_MS
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { vm.menuMove(-1); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { vm.menuMove(1); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // Только свежее нажатие, после отпускания и не сразу
                    // после открытия.
                    if (menuArmed && !repeat && settled) {
                        val item = vm.menuSelect()
                        if (item?.openSettings == true) showSettings = true
                    }
                    true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> { vm.closeMenu(); true }
                else -> true
            }
        }

        // OK — только запуск отслеживания; действие решается на отпускании.
        if (isOk(keyCode)) {
            okPressedHere = true
            event?.startTracking()
            return true
        }

        if (current.zoomed) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { vm.pan(-PAN_STEP, 0f); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { vm.pan(PAN_STEP, 0f); true }
                KeyEvent.KEYCODE_DPAD_UP -> { vm.pan(0f, -PAN_STEP); true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { vm.pan(0f, PAN_STEP); true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_ZOOM_IN,
                KeyEvent.KEYCODE_PAGE_UP -> { vm.zoomIn(); true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_ZOOM_OUT,
                KeyEvent.KEYCODE_PAGE_DOWN -> { vm.zoomOut(); true }
                KeyEvent.KEYCODE_BACK -> { vm.resetZoom(); true }
                KeyEvent.KEYCODE_MENU -> { openActionMenu(armed = true); true }
                else -> super.onKeyDown(keyCode, event)
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                vm.skip(); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                vm.back(); true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_ZOOM_IN, KeyEvent.KEYCODE_PAGE_UP -> {
                vm.zoomIn(); true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_ZOOM_OUT,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                // На обычном масштабе уменьшать нечего; кнопка занята,
                // чтобы не улетала в систему как переключение канала.
                true
            }
            KeyEvent.KEYCODE_MENU -> {
                // Кнопка «Меню» на пульте: меню действий над кадром
                // (скрыть, настройки). Открыта не удерживаемым OK, так что
                // подтверждать можно сразу.
                openActionMenu(armed = true); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                showSettings = true; true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        vm.stop()
        super.onDestroy()
    }

    private companion object {
        const val STARTUP_REINDEX_AFTER_MS = 60L * 60 * 1000

        /** Шаг сдвига увеличенного кадра: от края до края — восемь нажатий. */
        const val PAN_STEP = 0.25f

        /** Столько после открытия меню OK не принимается. */
        const val MENU_GRACE_MS = 600L
    }
}
