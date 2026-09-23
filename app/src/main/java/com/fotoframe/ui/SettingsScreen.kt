package com.fotoframe.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoframe.data.db.Photo
import kotlinx.coroutines.delay
import com.fotoframe.engine.City
import com.fotoframe.data.prefs.FitMode
import com.fotoframe.data.prefs.PhotoOrder
import com.fotoframe.data.prefs.SlideshowSettings
import com.fotoframe.data.prefs.Transition
import com.fotoframe.engine.PhotoPicker
import kotlin.math.roundToInt

/**
 * Экран настроек с двумя режимами.
 *
 * Простой режим показывает то, что меняют чаще всего: скорость, порядок,
 * вписывание кадра, наезд на лицо, переход. Этого хватает для повседневного
 * использования.
 *
 * Подробный режим раскрывает всё остальное блоками с заголовками, чтобы
 * глазами легко найти нужное. Переключатель режима — первая строка.
 */
@Composable
fun SettingsScreen(
    settings: SlideshowSettings,
    totalPhotos: Int,
    statusMessage: String?,
    onIntervalChange: (Int) -> Unit,
    onOrderChange: (PhotoOrder) -> Unit,
    onTransitionChange: (Transition) -> Unit,
    onTransitionMillisChange: (Int) -> Unit,
    onFitModeChange: (FitMode) -> Unit,
    onFaceFocusChange: (Boolean) -> Unit,
    onFitFaceZoomChange: (Boolean) -> Unit,
    onZoomStrengthChange: (Float) -> Unit,
    onZoomPaceChange: (Float) -> Unit,
    onZoomWithoutFaceChange: (Boolean) -> Unit,
    onSkipSimilarChange: (Boolean) -> Unit,
    onPairByContentChange: (Boolean) -> Unit,
    onShowWeatherChange: (Boolean) -> Unit,
    cities: List<City> = emptyList(),
    onCitySearch: (String) -> Unit = {},
    onCityPick: (City) -> Unit = {},
    onPortraitFitWholeChange: (Boolean) -> Unit,
    onPairPortraitsChange: (Boolean) -> Unit,
    onRecencyBiasChange: (Float) -> Unit,
    onFreshBoostChange: (Float) -> Unit,
    onFreshWindowChange: (Int) -> Unit,
    onRepeatCooldownChange: (Int) -> Unit,
    onMinMegapixelsChange: (Float) -> Unit,
    onMinFileSizeChange: (Int) -> Unit,
    onSkipJunkFoldersChange: (Boolean) -> Unit,
    onSkipPngChange: (Boolean) -> Unit,
    onMaxAspectChange: (Float) -> Unit,
    onShowClockChange: (Boolean) -> Unit,
    onShowDateChange: (Boolean) -> Unit,
    onShowPhotoDateChange: (Boolean) -> Unit,
    onShowLocationChange: (Boolean) -> Unit,
    onShowFileNameChange: (Boolean) -> Unit = {},
    onKeepScreenOnChange: (Boolean) -> Unit,
    onYandexTokenChange: (String) -> Unit,
    /** Сохранить токен и, дождавшись записи, открыть обзор папок. */
    onYandexConnect: (String) -> Unit,
    onSmbChange: (host: String, share: String, user: String, password: String, folder: String) -> Unit,
    /** Сохранить доступ и, дождавшись записи, открыть обзор папок. */
    onSmbConnect: (host: String, share: String, user: String, password: String) -> Unit,
    onReindex: () -> Unit,
    onReindexForce: () -> Unit,
    hiddenPhotos: List<Photo> = emptyList(),
    onUnhide: (Long) -> Unit = {},
    onUnhideAll: () -> Unit = {},
    onDeleteHidden: () -> Unit = {},
    /** Сколько записей от каждого источника в индексе. */
    sourceCounts: Map<String, Int> = emptyMap(),
    onRemoveSource: (String) -> Unit = {},
    onDiagnostics: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var advanced by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101214))
            .padding(horizontal = 64.dp, vertical = 40.dp)
    ) {

    // Отчёты и диагностика — закреплённой панелью сверху, вне прокрутки.
    // Раньше они были последним элементом списка и оказывались за нижним
    // краем экрана: нажимаешь «обновить» — и как будто ничего не происходит.
    StatusPanel(statusMessage)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text(
                    "Настройки рамки",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Light
                )
                Text(
                    "В коллекции $totalPhotos снимков",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 16.sp
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeTab("Простые", !advanced) { advanced = false }
                ModeTab("Все настройки", advanced) { advanced = true }
            }
        }

        // ======== Простой набор — всегда виден ========

        item { SectionTitle("Показ") }

        item {
            StepperRow(
                title = "Держать кадр",
                value = "${settings.intervalSeconds} с",
                onPrev = { onIntervalChange((settings.intervalSeconds - 5).coerceAtLeast(5)) },
                onNext = { onIntervalChange((settings.intervalSeconds + 5).coerceAtMost(600)) }
            )
        }

        item {
            ChoiceRow(
                title = "Порядок",
                options = PhotoOrder.values().toList(),
                selected = settings.order,
                labelOf = ::orderLabel,
                onSelect = onOrderChange
            )
        }

        item {
            ChoiceRow(
                title = "Кадр в экране",
                options = FitMode.values().toList(),
                selected = settings.fitMode,
                labelOf = ::fitLabel,
                onSelect = onFitModeChange
            )
        }

        item {
            SwitchRow(
                title = "Пропускать похожие снимки серии",
                subtitle = "Из десяти кадров, снятых подряд с одной точки, за цикл показывается один. " +
                    "Похожесть считается по отпечатку картинки, серия — та же папка и полторы " +
                    "минуты по времени съёмки. Отпечатки досчитываются постепенно",
                checked = settings.skipSimilar,
                onChange = onSkipSimilarChange
            )
        }

        item {
            SwitchRow(
                title = "Вертикальные снимки парами",
                subtitle = "Два вертикальных кадра рядом заполняют экран без обрезки " +
                    "и без полей. Если пары не нашлось, снимок показывается один",
                checked = settings.pairPortraits,
                onChange = onPairPortraitsChange
            )
        }

        if (settings.pairPortraits) {
            item {
                SwitchRow(
                    title = "Подбирать пару по содержимому",
                    subtitle = "Снимок с людьми встаёт рядом со снимком с людьми, а пейзаж " +
                        "рядом с пейзажем — вместо портрета рядом с тарелкой. Если подходящей " +
                        "пары нет, берётся любая. Выключено — пара случайная, как раньше",
                    checked = settings.pairByContent,
                    onChange = onPairByContentChange
                )
            }
        }

        item {
            SwitchRow(
                title = "Вертикальные снимки целиком",
                subtitle = "Когда пары нет: показывать полностью, с размытым фоном по бокам",
                checked = settings.portraitFitWhole,
                onChange = onPortraitFitWholeChange
            )
        }

        item {
            SwitchRow(
                title = "Наезд на лицо",
                subtitle = "В режиме «во весь экран с наездом» камера ведёт к лицу, " +
                    "если оно есть на снимке",
                checked = settings.faceFocus,
                onChange = onFaceFocusChange
            )
        }

        item {
            val z = settings.zoomStrength
            StepperRow(
                title = "Сила наезда",
                caption = when {
                    z <= 1.001f -> "Наезда нет, кадр стоит неподвижно"
                    z <= 1.10f -> "Едва заметное движение"
                    z <= 1.20f -> "Спокойное движение"
                    else -> "Заметный наезд"
                } + ". Темп задаётся интервалом смены: чем он больше, тем медленнее",
                value = if (z <= 1.001f) "выкл" else "+${((z - 1f) * 100).toInt()}%",
                onPrev = { onZoomStrengthChange((z - 0.04f).coerceAtLeast(1f)) },
                onNext = { onZoomStrengthChange((z + 0.04f).coerceAtMost(1.40f)) }
            )
        }

        item {
            WeatherCard(
                settings = settings,
                cities = cities,
                onShowWeatherChange = onShowWeatherChange,
                onSearch = onCitySearch,
                onPick = onCityPick
            )
        }

        item {
            val p = settings.zoomPace
            StepperRow(
                title = "Скорость наезда",
                caption = when {
                    p >= 0.95f -> "Движение растянуто на весь показ — самое спокойное"
                    p >= 0.65f -> "Наезд заканчивается раньше смены кадра"
                    p >= 0.35f -> "Быстрый наезд, дальше кадр стоит крупным планом"
                    else -> "Очень быстрый наезд в начале показа"
                } + ". При коротком интервале только этим и получается ускорить движение",
                value = "${(p * 100).toInt()}% показа",
                onPrev = { onZoomPaceChange((p - 0.15f).coerceAtLeast(0.15f)) },
                onNext = { onZoomPaceChange((p + 0.15f).coerceAtMost(1f)) }
            )
        }

        item {
            SwitchRow(
                title = "Наезд и на кадрах без лица",
                subtitle = "Если лица в кадре нет, камера наезжает к самому заметному месту " +
                    "снимка — горизонту, зданию, цветку; на ровном кадре — от центра. " +
                    "Выключено — такие снимки стоят неподвижно",
                checked = settings.zoomWithoutFace,
                onChange = onZoomWithoutFaceChange
            )
        }

        item {
            SwitchRow(
                title = "Наезд на лицо в режиме «целиком»",
                subtitle = "Горизонтальный снимок появляется весь, с полями, и медленно " +
                    "увеличивается к лицу, пока поля не уйдут. Без лица кадр стоит на месте",
                checked = settings.fitFaceZoom,
                onChange = onFitFaceZoomChange
            )
        }

        item {
            ChoiceRow(
                title = "Эффект смены",
                options = Transition.values().toList(),
                selected = settings.transition,
                labelOf = Transitions::label,
                onSelect = onTransitionChange
            )
        }

        // ======== Подробный набор ========

        item {
            AnimatedVisibility(visible = advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    SectionTitle("Приоритет новых снимков")

                    val share = PhotoPicker.medianShare(settings.recencyBias)
                    StepperRow(
                        title = "Насколько новые важнее старых",
                        caption = if (settings.recencyBias <= 1.05f)
                            "Отключено: все снимки одинаково часто"
                        else
                            "Половина показов — самые свежие ${(share * 100).roundToInt()}% коллекции",
                        value = if (settings.recencyBias <= 1.05f) "выкл"
                        else "%.1f".format(settings.recencyBias),
                        onPrev = { onRecencyBiasChange((settings.recencyBias - 0.5f).coerceAtLeast(1f)) },
                        onNext = { onRecencyBiasChange((settings.recencyBias + 0.5f).coerceAtMost(6f)) }
                    )

                    StepperRow(
                        title = "Доля показов для новинок",
                        caption = if (settings.freshBoost < 0.01f) "Отключено"
                        else "Каждый ${(1f / settings.freshBoost).roundToInt()}-й кадр — снимок, " +
                            "который рамка ещё не показывала",
                        value = "${(settings.freshBoost * 100).roundToInt()}%",
                        onPrev = { onFreshBoostChange((settings.freshBoost - 0.05f).coerceAtLeast(0f)) },
                        onNext = { onFreshBoostChange((settings.freshBoost + 0.05f).coerceAtMost(0.6f)) }
                    )

                    StepperRow(
                        title = "Снимок считается новым",
                        value = "${settings.freshWindowDays} дн.",
                        onPrev = { onFreshWindowChange((settings.freshWindowDays - 7).coerceAtLeast(1)) },
                        onNext = { onFreshWindowChange((settings.freshWindowDays + 7).coerceAtMost(180)) }
                    )

                    StepperRow(
                        title = "Не повторять снимок",
                        value = if (settings.repeatCooldownDays == 0) "выкл"
                        else "${settings.repeatCooldownDays} дн.",
                        onPrev = { onRepeatCooldownChange((settings.repeatCooldownDays - 7).coerceAtLeast(0)) },
                        onNext = { onRepeatCooldownChange((settings.repeatCooldownDays + 7).coerceAtMost(365)) }
                    )

                    SectionTitle("Что не пускать на экран")

                    SwitchRow(
                        "Скриншоты, загрузки, мессенджеры", null,
                        settings.skipJunkFolders, onSkipJunkFoldersChange
                    )
                    SwitchRow(
                        "Картинки PNG и WebP", null,
                        settings.skipPng, onSkipPngChange
                    )
                    StepperRow(
                        title = "Минимальное разрешение",
                        caption = if (settings.minMegapixels < 0.1f) "Без ограничения"
                        else "Меньше ${"%.1f".format(settings.minMegapixels)} Мп не показывать",
                        value = if (settings.minMegapixels < 0.1f) "выкл"
                        else "${"%.1f".format(settings.minMegapixels)} Мп",
                        onPrev = { onMinMegapixelsChange((settings.minMegapixels - 0.5f).coerceAtLeast(0f)) },
                        onNext = { onMinMegapixelsChange((settings.minMegapixels + 0.5f).coerceAtMost(12f)) }
                    )
                    StepperRow(
                        title = "Минимальный вес файла",
                        value = if (settings.minFileSizeKb == 0) "выкл" else "${settings.minFileSizeKb} КБ",
                        onPrev = { onMinFileSizeChange((settings.minFileSizeKb - 100).coerceAtLeast(0)) },
                        onNext = { onMinFileSizeChange((settings.minFileSizeKb + 100).coerceAtMost(5000)) }
                    )
                    StepperRow(
                        title = "Предельная вытянутость кадра",
                        caption = if (settings.maxAspectRatio <= 1f) "Без ограничения"
                        else "Отсекает штрихкоды и длинные скриншоты",
                        value = if (settings.maxAspectRatio <= 1f) "выкл"
                        else "${"%.2f".format(settings.maxAspectRatio)}:1",
                        onPrev = {
                            onMaxAspectChange(
                                if (settings.maxAspectRatio <= 1.25f) 0f
                                else settings.maxAspectRatio - 0.25f
                            )
                        },
                        onNext = {
                            onMaxAspectChange(
                                if (settings.maxAspectRatio < 1.25f) 1.25f
                                else (settings.maxAspectRatio + 0.25f).coerceAtMost(5f)
                            )
                        }
                    )

                    SectionTitle("Переход")

                    StepperRow(
                        title = "Длительность перехода",
                        value = "${settings.transitionMillis} мс",
                        onPrev = { onTransitionMillisChange((settings.transitionMillis - 200).coerceAtLeast(200)) },
                        onNext = { onTransitionMillisChange((settings.transitionMillis + 200).coerceAtMost(4000)) }
                    )

                    SectionTitle("Поверх снимка")

                    SwitchRow("Часы", null, settings.showClock, onShowClockChange)
                    SwitchRow("Сегодняшняя дата", null, settings.showDate, onShowDateChange)
                    SwitchRow(
                        "Дата съёмки",
                        "Только настоящая, из самого снимка. Дата копирования на хранилище не показывается",
                        settings.showPhotoDate, onShowPhotoDateChange
                    )
                    SwitchRow(
                        "Где снято",
                        "Город определяется по координатам из снимка, если они есть",
                        settings.showLocation, onShowLocationChange
                    )
                    SwitchRow(
                        "Имя файла",
                        "Для коллекций картин это обычно название работы. Без расширения, " +
                            "подчёркивания заменены пробелами",
                        settings.showFileName, onShowFileNameChange
                    )

                    SectionTitle("Экран")

                    SwitchRow(
                        "Не гасить экран",
                        "Иначе приставка уснёт по своему таймеру",
                        settings.keepScreenOn, onKeepScreenOnChange
                    )

                    SectionTitle("Яндекс.Диск")

                    var token by remember { mutableStateOf(settings.yandexToken.orEmpty()) }
                    RowCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Токен доступа", color = Color.White, fontSize = 20.sp)
                            Text(
                                "Получите токен на телефоне через oauth.yandex.ru и введите здесь. " +
                                    "Рамка запросит только чтение файлов.",
                                color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp
                            )
                            LabeledField("Токен", "y0_...", token) { token = it }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    if (token.isBlank()) "Токен не введён" else "Введено символов: ${token.length}",
                                    color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp
                                )
                                // Ввод через adb дописывает к уже введённому — от склейки
                                // двух токенов спасает только очистка.
                                if (token.isNotBlank()) {
                                    Button(onClick = { token = "" }) { Text("Очистить") }
                                }
                            }
                            Text(
                                "Папка: " + settings.yandexFolder.ifBlank { "весь Диск" },
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 16.sp
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { onYandexTokenChange(token) }) {
                                    Text("Сохранить токен")
                                }
                                Button(onClick = { onYandexConnect(token) }) {
                                    Text("Подключиться и выбрать папку")
                                }
                            }

                            SourceLeftovers(
                                configured = !settings.yandexToken.isNullOrBlank(),
                                count = sourceCounts["yandex"] ?: 0,
                                title = "Диска",
                                onRemove = { onRemoveSource("yandex") }
                            )
                        }
                    }

                    SectionTitle("Сетевая папка")

                    var host by remember { mutableStateOf(settings.smbHost) }
                    var user by remember { mutableStateOf(settings.smbUser) }
                    var password by remember { mutableStateOf(settings.smbPassword) }

                    RowCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Папка на сетевом хранилище", color = Color.White, fontSize = 20.sp)
                            Text(
                                "Работает по SMB2 и SMB3. Адрес — только имя или IP, " +
                                    "без smb:// в начале. Логин оставьте пустым для гостевого доступа. " +
                                    "Общую папку и папку внутри неё выбирают в обзоре.",
                                color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp
                            )
                            LabeledField("Адрес хранилища", "192.168.100.10", host) { host = it }
                            LabeledField("Пользователь", "", user) { user = it }
                            LabeledField("Пароль", "", password, isPassword = true) { password = it }

                            Text(
                                "Общая папка: " + settings.smbShare.ifBlank { "не выбрана" } +
                                    "   ·   Папка: " + settings.smbFolder.ifBlank { "вся общая папка" },
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 16.sp
                            )

                            SourceLeftovers(
                                configured = settings.smbHost.isNotBlank() && settings.smbShare.isNotBlank(),
                                count = sourceCounts["smb"] ?: 0,
                                title = "сетевой папки",
                                onRemove = { onRemoveSource("smb") }
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = {
                                    // Сохранение и обзор — одной цепочкой в Activity:
                                    // раньше обзор стартовал сразу и на чистой
                                    // установке читал ещё пустые настройки.
                                    onSmbConnect(host, settings.smbShare, user, password)
                                }) {
                                    Text("Подключиться и выбрать папку")
                                }
                                Button(onClick = {
                                    onSmbChange(host, settings.smbShare, user, password, settings.smbFolder)
                                }) {
                                    Text("Только сохранить")
                                }
                            }
                        }
                    }
                }
            }
        }

        item { SectionTitle("Обслуживание") }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onReindex) { Text("Обновить список фотографий") }
                Button(onClick = onDiagnostics) { Text("Диагностика") }
                Button(onClick = onClose) { Text("Вернуться к показу") }
            }
        }

        item {
            RowCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Удалить пропавшие снимки", color = Color.White, fontSize = 20.sp)
                    Text(
                        "Обычное обновление не удаляет записи, если из источника исчезло " +
                            "подозрительно много файлов — так рамка переживает выключенное " +
                            "хранилище. Если вы сами переложили или удалили архив, " +
                            "нажмите здесь: пропавшие записи уйдут вместе с историей показов.",
                        color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp
                    )
                    Button(onClick = onReindexForce) { Text("Обновить и удалить пропавшие") }
                }
            }
        }

        item {
            HiddenPhotosCard(hiddenPhotos, onUnhide, onUnhideAll, onDeleteHidden)
        }
    }
    }
}

/**
 * Панель состояния наверху экрана: отчёт об обновлении и диагностика.
 *
 * Свёрнута до одной строки. Отчёт об обновлении бывает на десяток строк,
 * и раскрытой панель занимала половину экрана планшета — а нужна она
 * обычно один раз, сразу после нажатия. Поэтому новое сообщение
 * показывается целиком [AUTO_COLLAPSE_MS], потом сворачивается само;
 * открыть и закрыть можно нажатием в любой момент.
 */
@Composable
private fun StatusPanel(message: String?) {
    if (message.isNullOrBlank()) return

    var expanded by remember(message) { mutableStateOf(true) }
    val multiline = message.contains('\n')

    // Сворачиваем сами, но только длинные: короткое «Готово» мельтешить
    // не будет, а прятать его незачем.
    LaunchedEffect(message) {
        if (multiline) {
            delay(AUTO_COLLAPSE_MS)
            expanded = false
        }
    }

    val firstLine = message.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B2A17)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp)
            .focusHighlight(RoundedCornerShape(14.dp))
            .clickable(enabled = multiline) { expanded = !expanded }
    ) {
        if (expanded) {
            Text(
                message,
                color = Color(0xFF9CCC65),
                fontSize = 16.sp,
                modifier = Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            )
        } else {
            Text(
                firstLine + "   ·   нажмите, чтобы раскрыть",
                color = Color(0xFF9CCC65),
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}

/** Через сколько длинный отчёт сворачивается сам. */
private const val AUTO_COLLAPSE_MS = 8_000L

/**
 * Поле ввода, пригодное для пульта.
 *
 * На телевизоре фокус ходит по D-pad, и обычного тапа нет. Поэтому по всей
 * строке висит обработчик нажатия, который сам запрашивает фокус и поднимает
 * экранную клавиатуру — иначе на части прошивок она не появляется.
 */
@Composable
private fun LabeledField(
    label: String,
    placeholder: String,
    value: String,
    isPassword: Boolean = false,
    onChange: (String) -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Пароль можно показать: с экранной клавиатуры на ТВ опечатка —
    // обычное дело, а звёздочки её не выдают. Кнопка текстовая, а не
    // значок: до неё надо доходить пультом, и подпись понятнее глаза.
    var reveal by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
        TextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            placeholder = { Text(placeholder, color = Color.White.copy(alpha = 0.3f)) },
            visualTransformation = if (isPassword && !reveal) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            trailingIcon = if (isPassword) {
                {
                    androidx.compose.material3.TextButton(onClick = { reveal = !reveal }) {
                        Text(if (reveal) "Скрыть" else "Показать")
                    }
                }
            } else {
                null
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = if (isPassword) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { keyboard?.hide() }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .clickable {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
        )
    }
}

@Composable
private fun ModeTab(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .focusHighlight(RoundedCornerShape(10.dp))
            .background(
                if (active) Color(0xFF3D6FE0) else Color(0xFF262A2F),
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(
            text,
            color = if (active) Color.White else Color.White.copy(alpha = 0.7f),
            fontSize = 17.sp
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp)
    )
}

/**
 * Погода поверх кадра. Город ищется по названию: вводить координаты с
 * пульта никто не станет, а определять их по снимкам ненадёжно — архив
 * может быть весь из отпусков.
 */
@Composable
private fun WeatherCard(
    settings: SlideshowSettings,
    cities: List<City>,
    onShowWeatherChange: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
    onPick: (City) -> Unit
) {
    var query by remember { mutableStateOf("") }

    RowCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Погода", color = Color.White, fontSize = 20.sp)
                    Text(
                        if (settings.weatherPlace.isBlank()) {
                            "Температура рядом с часами. Выберите город"
                        } else {
                            "Город: ${settings.weatherPlace}"
                        },
                        color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp
                    )
                }
                Switch(checked = settings.showWeather, onCheckedChange = onShowWeatherChange)
            }

            if (settings.showWeather) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(Modifier.weight(1f)) {
                        LabeledField("Город", "Санкт-Петербург", query) { query = it }
                    }
                    Button(onClick = { onSearch(query) }) { Text("Найти") }
                }

                cities.forEach { city ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .focusHighlight(RoundedCornerShape(10.dp))
                            .background(Color(0xFF1B1E22), RoundedCornerShape(10.dp))
                            .clickable { onPick(city); query = "" }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Column {
                            Text(city.name, color = Color.White, fontSize = 18.sp)
                            if (city.region.isNotBlank()) {
                                Text(
                                    city.region,
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }
                }

                Text(
                    "Данные Open-Meteo, обновляются раз в полчаса. Без сети подпись " +
                        "просто не показывается.",
                    color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Записи отключённого источника, оставшиеся в индексе. В показ они не
 * идут, но раздувают счётчики; удаляются в два нажатия.
 */
@Composable
private fun SourceLeftovers(
    configured: Boolean,
    count: Int,
    title: String,
    onRemove: () -> Unit
) {
    if (configured || count == 0) return
    var armed by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Источник отключён, но в индексе осталось $count снимков $title. В показ они " +
                "не идут; удалите их, если не собираетесь подключать источник снова.",
            color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp
        )
        if (armed) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { armed = false }) { Text("Нет, оставить") }
                Button(onClick = { armed = false; onRemove() }) { Text("Да, удалить из индекса") }
            }
        } else {
            Button(onClick = { armed = true }) { Text("Удалить снимки $title из индекса") }
        }
    }
}

/**
 * Скрытые с пульта снимки. Файлы на месте; «Вернуть» снимает пометку.
 */
@Composable
private fun HiddenPhotosCard(
    hidden: List<Photo>,
    onUnhide: (Long) -> Unit,
    onUnhideAll: () -> Unit,
    onDeleteHidden: () -> Unit
) {
    // Удаление файлов — в два нажатия: кнопка сперва превращается в
    // подтверждение. Одного случайного нажатия на пульте мало.
    var armed by remember { mutableStateOf(false) }

    RowCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Скрытые снимки" + if (hidden.isNotEmpty()) " (${hidden.size})" else "",
                    color = Color.White, fontSize = 20.sp
                )
                if (hidden.isNotEmpty()) {
                    Button(onClick = onUnhideAll) { Text("Вернуть все") }
                }
            }
            Text(
                "Кнопка «Меню» на пульте открывает меню над кадром, где снимок можно скрыть " +
                    "или сразу удалить. Скрытие файл не трогает.",
                color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp
            )

            if (hidden.isNotEmpty()) {
                if (armed) {
                    Text(
                        "Убрать ${hidden.size} файлов из хранилища? С сетевой папки они " +
                            "переедут в «${com.fotoframe.source.SmbSource.TRASH_DIR}», с Яндекс.Диска — " +
                            "в Корзину Диска. Снимки с самого устройства пропускаются: " +
                            "система требует подтверждать каждый отдельно, их удаляйте с кадра.",
                        color = Color(0xFFFF8A80), fontSize = 15.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = { armed = false }) { Text("Нет, оставить") }
                        Button(onClick = { armed = false; onDeleteHidden() }) {
                            Text("Да, убрать из хранилища")
                        }
                    }
                } else {
                    Button(onClick = { armed = true }) { Text("Удалить скрытые с хранилища") }
                }
            }
            if (hidden.isEmpty()) {
                Text("Пока ничего не скрыто", color = Color.White.copy(alpha = 0.4f), fontSize = 15.sp)
            }
            hidden.forEach { photo ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(photo.displayName, color = Color.White, fontSize = 16.sp)
                        Text(
                            listOfNotNull(photo.albumName, sourceTitle(photo.sourceId))
                                .joinToString(" · "),
                            color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp
                        )
                    }
                    Button(onClick = { onUnhide(photo.id) }) { Text("Вернуть") }
                }
            }
        }
    }
}

private fun sourceTitle(sourceId: String): String = when (sourceId) {
    "local" -> "Устройство"
    "yandex" -> "Яндекс.Диск"
    "smb" -> "Сетевая папка"
    else -> sourceId
}

@Composable
private fun RowCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1E22)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.padding(20.dp)) { content() }
    }
}

/**
 * Шаг «минус / плюс». Все числовые настройки сделаны так, а не ползунком:
 * ползунок Material 3 на пульте с D-pad ведёт себя ненадёжно, а две кнопки
 * работают везде.
 */
@Composable
private fun StepperRow(
    title: String,
    value: String,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    caption: String? = null
) {
    RowCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontSize = 20.sp)
                if (caption != null) {
                    Text(caption, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onPrev) { Text("−") }
                Text(value, color = Color.White, fontSize = 20.sp, modifier = Modifier.width(96.dp))
                Button(onClick = onNext) { Text("+") }
            }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    RowCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = Color.White, fontSize = 20.sp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.chunked(2).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowItems.forEach { option ->
                            val active = option == selected
                            Box(
                                Modifier
                                    .focusHighlight(RoundedCornerShape(8.dp))
                                    .background(
                                        if (active) Color(0xFF3D6FE0) else Color(0xFF262A2F),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { onSelect(option) }
                                    .padding(horizontal = 18.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    labelOf(option),
                                    color = if (active) Color.White else Color.White.copy(alpha = 0.7f),
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    RowCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontSize = 20.sp)
                if (subtitle != null) {
                    Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp)
                }
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

private fun orderLabel(order: PhotoOrder): String = when (order) {
    PhotoOrder.RANDOM -> "Случайно"
    PhotoOrder.RANDOM_RECENT_FIRST -> "Случайно, новые чаще"
    PhotoOrder.NEWEST_FIRST -> "От новых к старым"
}

private fun fitLabel(mode: FitMode): String = when (mode) {
    FitMode.FIT_BLUR -> "Целиком, размытый фон"
    FitMode.FIT_BLACK -> "Целиком, чёрные поля"
    FitMode.FILL -> "Во весь экран"
    FitMode.KEN_BURNS -> "Во весь экран с наездом"
}
