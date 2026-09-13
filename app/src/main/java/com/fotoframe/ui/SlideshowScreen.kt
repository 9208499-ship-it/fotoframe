package com.fotoframe.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoframe.engine.ActionMenu
import com.fotoframe.engine.SlideshowState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SlideshowScreen(
    state: SlideshowState,
    onLongPress: () -> Unit = {},
    onTap: () -> Unit = {},
    /** Выбран пункт меню действий пальцем. */
    onMenuPick: (Int) -> Unit = {},
    onMenuClose: () -> Unit = {},
    onSwipeNext: () -> Unit = {},
    onSwipePrev: () -> Unit = {},
    onPinchIn: () -> Unit = {},
    onPinchOut: () -> Unit = {},
    onLoadError: (photoId: Long) -> Unit = {},
    onLoadSuccess: (photoId: Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { onLongPress() },
                    onTap = { onTap() }
                )
            }
            // Приложение писалось под пульт, но его ставят и на планшеты.
            // Свайп листает как стрелки, щипок работает как каналы +/−.
            //
            // Оба жеста в одном обработчике намеренно. Готовый
            // распознаватель щипка реагирует и на движение одним пальцем
            // и забирает событие себе, поэтому рядом с ним свайп не
            // срабатывал вовсе. Здесь пальцы считаются вручную: один —
            // листание, два — масштаб.
            .pointerInput(Unit) {
                val swipeThreshold = SWIPE_THRESHOLD_DP.dp.toPx()

                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)

                    var pan = 0f
                    var zoomAcc = 1f
                    var maxPointers = 1
                    var pressed = true

                    while (pressed) {
                        val event = awaitPointerEvent()
                        val active = event.changes.count { it.pressed }
                        maxPointers = maxOf(maxPointers, active)

                        if (active >= 2) {
                            // Щипок отдаёт коэффициент около единицы на
                            // каждое движение; ступень меняем, когда
                            // накопится заметное изменение, иначе масштаб
                            // проскакивает все ступени за один жест.
                            zoomAcc *= event.calculateZoom()
                            when {
                                zoomAcc >= PINCH_STEP -> { onPinchIn(); zoomAcc = 1f }
                                zoomAcc <= 1f / PINCH_STEP -> { onPinchOut(); zoomAcc = 1f }
                            }
                            event.changes.forEach { it.consume() }
                        } else if (maxPointers == 1) {
                            pan += event.calculatePan().x
                        }

                        pressed = event.changes.any { it.pressed }
                    }

                    // Вторым пальцем это был масштаб, листать не надо.
                    if (maxPointers == 1) {
                        when {
                            pan <= -swipeThreshold -> onSwipeNext()
                            pan >= swipeThreshold -> onSwipePrev()
                        }
                    }
                }
            }
    ) {
        val slide = state.current

        if (slide == null) {
            EmptyState(state.message)
            return@Box
        }

        // Случайный эффект разыгрывается один раз на кадр: вход нового
        // и выход старого используют одну и ту же пару анимаций.
        val resolvedTransition = remember(slide.photo.id) {
            Transitions.concrete(state.settings.transition)
        }

        AnimatedContent(
            targetState = slide,
            transitionSpec = {
                Transitions.enterFor(resolvedTransition, state.settings.transitionMillis)
                    .togetherWith(
                        Transitions.exitFor(resolvedTransition, state.settings.transitionMillis)
                    )
            },
            label = "slide"
        ) { target ->
            // Увеличение относится только к кадру на экране; уходящему
            // кадру в анимации перехода оно не передаётся.
            val isCurrent = target.photo.id == slide.photo.id
            val effect = transitionEffect(resolvedTransition, state.settings.transitionMillis)
            val flash = flashAlpha(resolvedTransition, state.settings.transitionMillis)

            Box(effect) {
            SlideImage(
                slide = target,
                fitMode = state.settings.fitMode,
                faceFocus = state.settings.faceFocus,
                fitFaceZoom = state.settings.fitFaceZoom,
                portraitFitWhole = state.settings.portraitFitWhole,
                intervalMillis = state.settings.intervalSeconds * 1000,
                zoomStrength = state.settings.zoomStrength,
                zoomPace = state.settings.zoomPace,
                zoomWithoutFace = state.settings.zoomWithoutFace,
                zoom = if (isCurrent) state.zoom else 1f,
                panX = if (isCurrent) state.panX else 0f,
                panY = if (isCurrent) state.panY else 0f,
                onLoadError = { onLoadError(target.photo.id) },
                onLoadSuccess = { onLoadSuccess(target.photo.id) }
            )
            if (flash > 0.01f) {
                Box(Modifier.matchParentSize().background(Color.White.copy(alpha = flash)))
            }
            }
        }

        Overlay(state, Modifier.align(Alignment.BottomStart))

        if (state.zoomed) ZoomBadge(state.zoom, Modifier.align(Alignment.TopEnd))

        // Пауза без значка выглядит как зависшая рамка — так и было:
        // случайное нажатие OK останавливало показ, и понять это было
        // невозможно.
        if (state.paused || state.inHistory) {
            PauseBadge(state.inHistory, Modifier.align(Alignment.TopEnd))
        }

        // Сообщение поверх кадра — например, что снимки не загружаются.
        // Без кадра оно показывается в EmptyState.
        state.message?.let { Notice(it, Modifier.align(Alignment.TopStart)) }

        state.menu?.let {
            // Затемнение под меню: касание мимо пунктов закрывает его,
            // как «Назад» на пульте. Заодно перехватывает жесты, чтобы
            // под открытым меню не листались кадры.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .pointerInput(state.menu) {
                        detectTapGestures(onTap = { onMenuClose() })
                    }
            )
            ActionMenuOverlay(it, onMenuPick, Modifier.align(Alignment.Center))
        }

        // У пары подпись левого снимка стоит слева под часами, правого —
        // в своём углу: иначе дата и место относились бы непонятно к чему.
        slide.second?.let { right ->
            SecondCaption(state, right, Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun Overlay(state: SlideshowState, modifier: Modifier = Modifier) {
    val settings = state.settings
    if (!settings.showClock && !settings.showDate &&
        !settings.showPhotoDate && !settings.showLocation &&
        state.weather == null
    ) return

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        // Просыпаемся по границе минуты: часы переключаются точно,
        // а не с опозданием до десяти секунд.
        while (true) {
            val t = System.currentTimeMillis()
            now = t
            delay(60_000 - t % 60_000 + 50)
        }
    }

    Column(
        modifier = modifier.padding(40.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (settings.showClock) {
            Text(
                text = timeFormat.format(Date(now)),
                fontSize = 64.sp,
                weight = FontWeight.Light
            )
        }
        if (settings.showDate) {
            Text(
                text = dateFormat.format(Date(now)).replaceFirstChar { it.uppercase() },
                fontSize = 22.sp
            )
        }
        // Погода сразу под часами и датой: это про «сейчас», в отличие
        // от подписей снимка ниже, которые про «тогда».
        state.weather?.let {
            Text(text = it.line, fontSize = 22.sp, alpha = 0.85f)
        }
        val photo = state.current?.photo
        // Только настоящая дата съёмки: у файлов с сетевой папки до разбора
        // EXIF в takenAt лежит дата копирования, и её показывать не надо.
        if (settings.showPhotoDate && photo != null && photo.takenAtExact) {
            Text(
                text = photoDateFormat.format(Date(photo.takenAt)),
                fontSize = 18.sp,
                alpha = 0.75f
            )
        }
        if (settings.showLocation && !photo?.placeName.isNullOrBlank()) {
            Text(
                text = photo!!.placeName!!,
                fontSize = 18.sp,
                alpha = 0.75f
            )
        }
    }
}

/**
 * Меню действий над кадром. Управляется только с пульта: вверх/вниз —
 * выбор, OK — выполнить, «назад» — закрыть.
 */
@Composable
private fun ActionMenuOverlay(
    menu: ActionMenu,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Color(0xE6151719), RoundedCornerShape(16.dp))
            .padding(24.dp)
            .width(420.dp)
            // Касания по самому меню не должны доходить до затемнения
            // под ним, иначе любой промах между пунктами закрывал бы его.
            .pointerInput(Unit) { detectTapGestures { } },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.Text(
            text = if (menu.confirm) "Удалить файл из хранилища?" else "Что сделать с кадром?",
            color = if (menu.confirm) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.6f),
            fontSize = 16.sp
        )
        menu.subtitle?.let {
            androidx.compose.material3.Text(
                text = it,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        menu.items.forEachIndexed { index, item ->
            val active = index == menu.selected
            // Удаление подсвечивается красным, чтобы не перепутать со скрытием.
            val accent = if (item.deleteId != null && menu.confirm) {
                Color(0xFFB3261E)
            } else {
                Color(0xFF3D6FE0)
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (active) accent else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    // Пальцем — сразу нужный пункт, без перебора стрелками.
                    .clickable { onPick(index) }
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                androidx.compose.material3.Text(
                    text = item.label,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
        androidx.compose.material3.Text(
            text = if (menu.confirm) {
                "«Назад» или касание мимо меню — отменить"
            } else {
                "Скрытый снимок остаётся в хранилище, вернуть его можно в настройках. " +
                    "«Назад» или касание мимо меню — закрыть"
            },
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Две полоски, как на любом плеере, плюс подсказка, чем продолжить. */
@Composable
private fun PauseBadge(inHistory: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(40.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.width(6.dp).height(22.dp).background(Color.White))
        Box(Modifier.width(6.dp).height(22.dp).background(Color.White))
        androidx.compose.material3.Text(
            text = if (inHistory) "Просмотр истории — OK вернёт к показу" else "Пауза — OK продолжит",
            color = Color.White,
            fontSize = 18.sp
        )
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(40.dp)
            .background(Color(0xCC3A2226), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        androidx.compose.material3.Text(text = text, color = Color(0xFFFF8A80), fontSize = 18.sp)
    }
}

/**
 * Сколько нужно провести пальцем, чтобы это считалось свайпом.
 * В dp, а не в пикселях: на планшете с плотным экраном сотня пикселей —
 * это несколько миллиметров, и кадр листался бы от любого касания.
 */
private const val SWIPE_THRESHOLD_DP = 48

/** Во сколько раз должны разойтись пальцы для смены ступени. */
private const val PINCH_STEP = 1.25f

/**
 * Метка масштаба в углу — чтобы было видно, что кадр увеличен и стрелки
 * сейчас двигают его, а не листают.
 */
@Composable
private fun ZoomBadge(zoom: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(40.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        androidx.compose.material3.Text(
            text = "×" + (if (zoom % 1f == 0f) zoom.toInt().toString() else "%.1f".format(zoom)) +
                "   стрелки — сдвиг, OK — обычный размер",
            color = Color.White,
            fontSize = 18.sp
        )
    }
}

/** Подпись второго снимка пары — у своего края экрана. */
@Composable
private fun SecondCaption(
    state: SlideshowState,
    photo: com.fotoframe.data.db.Photo,
    modifier: Modifier = Modifier
) {
    val settings = state.settings
    val showDate = settings.showPhotoDate && photo.takenAtExact
    val showPlace = settings.showLocation && !photo.placeName.isNullOrBlank()
    if (!showDate && !showPlace) return

    Column(
        modifier = modifier.padding(40.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (showDate) {
            Text(
                text = photoDateFormat.format(Date(photo.takenAt)),
                fontSize = 18.sp,
                alpha = 0.75f
            )
        }
        if (showPlace) {
            Text(text = photo.placeName!!, fontSize = 18.sp, alpha = 0.75f)
        }
    }
}

/** Тонкая тень под текстом — иначе светлые снимки съедают подписи. */
@Composable
private fun Text(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    weight: FontWeight = FontWeight.Normal,
    alpha: Float = 1f
) {
    Box {
        androidx.compose.material3.Text(
            text = text,
            fontSize = fontSize,
            fontWeight = weight,
            color = Color.Black.copy(alpha = 0.5f * alpha),
            modifier = Modifier.padding(start = 2.dp, top = 2.dp)
        )
        androidx.compose.material3.Text(
            text = text,
            fontSize = fontSize,
            fontWeight = weight,
            color = Color.White.copy(alpha = alpha)
        )
    }
}

@Composable
private fun EmptyState(message: String?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.compose.material3.Text(
                text = "Показывать пока нечего",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Light
            )
            androidx.compose.material3.Text(
                text = message ?: "Нажмите кнопку меню, чтобы выбрать источник фотографий.",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 18.sp
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
private val photoDateFormat = SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
