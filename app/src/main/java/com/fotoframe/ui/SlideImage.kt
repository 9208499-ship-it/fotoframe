package com.fotoframe.ui

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fotoframe.data.db.Photo
import com.fotoframe.data.prefs.FitMode
import com.fotoframe.engine.Slide

/**
 * Один кадр слайдшоу.
 *
 * Пара вертикальных. Два таких снимка ставятся рядом целиком, в своих
 * настоящих пропорциях: ничего не обрезается и не растягивается. Половина
 * широкого экрана всё же шире вертикального кадра, и эта разница уходит в
 * поля по краям, а не в срезанные верх и низ. Между снимками — белая
 * полоса, чтобы взгляд сразу видел два отдельных кадра, а не один
 * непонятный. Пару подбирает SlideshowViewModel; сюда она приходит
 * готовой.
 *
 * Одиночные вертикальные снимки. Кадр, снятый с телефоном в вертикальном положении,
 * на широком экране при заполнении теряет около половины изображения —
 * обрезка тут не выбирает «что важнее», она просто отрезает всё, кроме
 * средней полосы. Поэтому по умолчанию такие снимки показываются целиком,
 * а поля по бокам заполняются размытым продолжением самого снимка. Это
 * отключается настройкой, если поля мешают больше, чем потеря краёв.
 *
 * Кадрирование. ContentScale.Crop сам по себе режет от центра, и у
 * вертикальных снимков на горизонтальном экране головы уходят за верхний
 * край. Поэтому окно кадрирования смещается к найденному детектором лицу,
 * а если данных о лицах нет — у портретов к верхней трети.
 *
 * Про арифметику смещения. BiasAlignment двигает не окно по картинке, а
 * картинку относительно экрана, и отсчитывает смещение от разницы их
 * размеров, а не от размера картинки. Поэтому доля кадра, в которой
 * находится лицо, переводится в сдвиг с поправкой на то, насколько
 * картинка выступает за экран: без этой поправки — а раньше её тут не
 * было — сдвиг выходил в разы меньше нужного, и лицо всё равно
 * оказывалось у края или за ним.
 *
 * Размеры снимка берутся из индекса, а если их там нет (сетевая папка не
 * сообщает размеры при обходе), то у самой загруженной картинки.
 *
 * Наезд идёт от той точки экрана, где после кадрирования оказалось лицо, —
 * тогда при увеличении оно стоит на месте, а расходятся от него края.
 *
 * Наезд в режимах «целиком». Горизонтальный снимок с найденным лицом
 * появляется весь, с полями, и за время показа увеличивается к лицу —
 * ровно настолько, чтобы поля ушли (потолок ×1,35, чтобы узкие снимки не
 * разгонялись в мыло). Масштаб идёт от центра, а лицо подводится к
 * середине сдвигом в пределах выступа — так ни с одной стороны не
 * появляется щель. Арифметика в [fitZoomPlan].
 *
 * Ручное увеличение с пульта ложится на тот же graphicsLayer: масштаб
 * умножается на [zoom], а [panX]/[panY] переводятся в сдвиг так, чтобы
 * кадр не уезжал за край экрана. Пока кадр увеличен, наезд Кена Бёрнса
 * не идёт — он бы спорил с рукой на пульте.
 *
 * @param onLoadError картинка не открылась — показ решит, что делать.
 */
@Composable
fun SlideImage(
    slide: Slide,
    fitMode: FitMode,
    faceFocus: Boolean,
    portraitFitWhole: Boolean,
    intervalMillis: Int,
    fitFaceZoom: Boolean = true,
    /** Глубина наезда за показ; 1.0 — движения нет. */
    zoomStrength: Float = 1.12f,
    zoom: Float = 1f,
    panX: Float = 0f,
    panY: Float = 0f,
    onLoadError: () -> Unit = {},
    onLoadSuccess: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Плавно: скачок масштаба на большом экране режет глаз.
    val zoomAnim by animateFloatAsState(zoom, tween(250), label = "zoom")
    val panXAnim by animateFloatAsState(panX, tween(200), label = "panX")
    val panYAnim by animateFloatAsState(panY, tween(200), label = "panY")

    if (slide.isPair) {
        PairOfPortraits(slide, fitMode, zoomAnim, panXAnim, panYAnim, onLoadError, onLoadSuccess, modifier)
        return
    }

    SinglePhoto(
        photo = slide.photo,
        url = slide.displayUrl,
        fitMode = fitMode,
        faceFocus = faceFocus,
        fitFaceZoom = fitFaceZoom,
        zoomStrength = zoomStrength,
        portraitFitWhole = portraitFitWhole,
        intervalMillis = intervalMillis,
        zoom = zoomAnim,
        panX = panXAnim,
        panY = panYAnim,
        onLoadError = onLoadError,
        onLoadSuccess = onLoadSuccess,
        modifier = modifier
    )
}

/**
 * Сдвиг увеличенного содержимого в пикселях. При масштабе z вокруг центра
 * с каждой стороны за экран выходит (z − 1) / 2 размера; pan = ±1 — это
 * ровно до края.
 */
private fun panOffset(size: Float, zoom: Float, pan: Float): Float =
    -pan * size * (zoom - 1f) / 2f

/** Слой ручного увеличения поверх любого содержимого кадра. */
private fun Modifier.manualZoom(zoom: Float, panX: Float, panY: Float): Modifier =
    if (zoom <= 1.001f) this else graphicsLayer {
        scaleX = zoom
        scaleY = zoom
        transformOrigin = TransformOrigin.Center
        translationX = panOffset(size.width, zoom, panX)
        translationY = panOffset(size.height, zoom, panY)
    }

/**
 * Два вертикальных снимка рядом, каждый целиком.
 *
 * Высота берётся общая для обоих и подгоняется так, чтобы пара вместе с
 * разделителем поместилась по ширине. Ширина каждого считается из его
 * собственных пропорций, поэтому снимки не приводятся к одному размеру:
 * более узкий останется более узким, как он и снят.
 */
@Composable
private fun PairOfPortraits(
    slide: Slide,
    fitMode: FitMode,
    zoom: Float,
    panX: Float,
    panY: Float,
    onLoadError: () -> Unit,
    onLoadSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Поля по краям у пары шире, чем у одиночного кадра, и сплошной
        // чёрный на телевизоре выглядит провалом. Заполняем их размытым
        // продолжением: каждая половина берёт фон от своего снимка.
        if (fitMode != FitMode.FIT_BLACK) {
            Row(Modifier.fillMaxSize()) {
                BlurredBackdrop(slide.displayUrl, Modifier.weight(1f).fillMaxHeight())
                BlurredBackdrop(slide.secondUrl.orEmpty(), Modifier.weight(1f).fillMaxHeight())
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }

        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()

        val separatorPx = with(density) { PAIR_SEPARATOR.toPx() }
        val marginPx = with(density) { PAIR_MARGIN.toPx() }

        val aspectLeft = aspectOf(slide.photo)
        val aspectRight = aspectOf(slide.second)

        // Сначала пробуем во всю доступную высоту; если по ширине не
        // помещается — уменьшаем обоих одинаково, сохраняя пропорции.
        var height = (boxH - marginPx * 2f).coerceAtLeast(1f)
        val available = (boxW - marginPx * 2f - separatorPx).coerceAtLeast(1f)
        val widthAtFullHeight = height * (aspectLeft + aspectRight)
        if (widthAtFullHeight > available) {
            height = available / (aspectLeft + aspectRight)
        }

        val heightDp = with(density) { height.toDp() }

        // Пара увеличивается как одно целое — вместе с разделителем.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.manualZoom(zoom, panX, panY)
        ) {
            PhotoTile(
                url = slide.displayUrl,
                description = slide.photo.displayName,
                width = with(density) { (height * aspectLeft).toDp() },
                height = heightDp,
                onLoadError = onLoadError,
                onLoadSuccess = onLoadSuccess
            )
            Box(
                Modifier
                    .width(PAIR_SEPARATOR)
                    .height(heightDp)
                    .background(Color.White)
            )
            PhotoTile(
                url = slide.secondUrl.orEmpty(),
                description = slide.second?.displayName.orEmpty(),
                width = with(density) { (height * aspectRight).toDp() },
                height = heightDp,
                onLoadError = onLoadError,
                onLoadSuccess = {}
            )
        }
    }
}

/**
 * Размытая подложка под половину экрана. Размытие даёт само
 * масштабирование крошечной копии, поэтому работает и до Android 12,
 * где Modifier.blur ничего не делает.
 */
@Composable
private fun BlurredBackdrop(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val blurred = modifier.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) it.blur(24.dp) else it
    }
    AsyncImage(
        model = ImageRequest.Builder(context).data(url).size(24, 32).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = blurred
    )
}

/** Снимок точно в заданном прямоугольнике — он уже посчитан по пропорциям. */
@Composable
private fun PhotoTile(
    url: String,
    description: String,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    onLoadError: () -> Unit,
    onLoadSuccess: () -> Unit
) {
    AsyncImage(
        model = url,
        contentDescription = description,
        contentScale = ContentScale.Fit,
        onError = { onLoadError() },
        onSuccess = { onLoadSuccess() },
        modifier = Modifier.width(width).height(height)
    )
}

/**
 * Пропорции снимка. Размеры для пары всегда известны — без них снимок
 * в пару не берётся, — но подстраховка на случай нулей не повредит.
 */
private fun aspectOf(photo: com.fotoframe.data.db.Photo?): Float {
    if (photo == null || photo.width <= 0 || photo.height <= 0) return 0.75f
    return (photo.width.toFloat() / photo.height.toFloat()).coerceIn(0.3f, 1f)
}

/**
 * Один снимок в отведённой ему области — на весь экран или в половину,
 * если показывается пара. Вся арифметика кадрирования считается от
 * размеров этой области, поэтому для половины экрана всё работает так же,
 * как для целого.
 */
@Composable
private fun SinglePhoto(
    photo: Photo,
    url: String,
    fitMode: FitMode,
    faceFocus: Boolean,
    fitFaceZoom: Boolean,
    zoomStrength: Float,
    portraitFitWhole: Boolean,
    intervalMillis: Int,
    zoom: Float,
    panX: Float,
    panY: Float,
    onLoadError: () -> Unit,
    onLoadSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()

        // Размеры из индекса, если известны. Иначе их сообщит загрузчик,
        // когда картинка будет разжата.
        var imageW by remember(photo.id) { mutableStateOf(photo.width.toFloat()) }
        var imageH by remember(photo.id) { mutableStateOf(photo.height.toFloat()) }

        val knownSize = imageW > 0f && imageH > 0f
        val portrait = if (knownSize) imageH > imageW else photo.isPortrait

        // Насколько снимок уже экрана: обрезка отрежет ровно эту долю,
        // поэтому целиком показываем только когда потеря заметная.
        val screenAspect = if (boxH > 0f) boxW / boxH else 16f / 9f
        val imageAspect = when {
            knownSize -> imageW / imageH
            portrait -> 0.75f
            else -> 1.5f
        }
        val losesMuch = imageAspect < screenAspect * 0.9f

        // Что показываем на самом деле: вертикальные снимки вписываем
        // целиком, для остальных остаётся выбранный режим.
        val effective = if (
            portraitFitWhole && portrait && losesMuch &&
            (fitMode == FitMode.FILL || fitMode == FitMode.KEN_BURNS)
        ) {
            FitMode.FIT_BLUR
        } else {
            fitMode
        }

        if (effective == FitMode.FIT_BLUR) {
            // Фон — крошечная копия снимка, растянутая на весь экран:
            // размытие даёт само масштабирование, и это работает на любой
            // версии Android. Modifier.blur добавляет гладкости, но до
            // Android 12 он ничего не делает, поэтому опираться только
            // на него нельзя.
            val bg = Modifier.fillMaxSize().let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) it.blur(24.dp) else it
            }
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .size(48, 27)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = bg
            )
            // Затемнение отдельным слоем поверх фона: background в цепочке
            // модификаторов картинки рисуется под ней и не виден.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }

        val manual = zoom > 1.001f
        // Пока кадр увеличен вручную, автоматический наезд не идёт;
        // при силе 1.0 его нет вовсе.
        val kenBurns = effective == FitMode.KEN_BURNS && !manual && zoomStrength > 1.001f
        val cropped = effective == FitMode.KEN_BURNS || effective == FitMode.FILL
        val hasFace = faceFocus && photo.faceCount > 0 && photo.focusY >= 0f

        // Куда смотреть в долях кадра: на лицо, а без лиц у вертикальных
        // снимков — на верхнюю треть, где лица обычно и оказываются.

        val focusX = if (hasFace) photo.focusX else 0.5f
        val focusY = when {
            hasFace -> photo.focusY
            portrait -> 0.33f
            else -> 0.5f
        }

        val crop = remember(photo.id, boxW, boxH, imageW, imageH, focusX, focusY, cropped) {
            if (cropped && knownSize && boxW > 0f && boxH > 0f) {
                cropPlacement(boxW, boxH, imageW, imageH, focusX, focusY)
            } else {
                null
            }
        }

        val alignment = when {
            crop != null -> BiasAlignment(crop.biasX, crop.biasY)
            // Размеры ещё неизвестны — грубое приближение по старой эвристике.
            cropped && portrait -> BiasAlignment(0f, -0.5f)
            else -> Alignment.Center
        }

        // Наезд ведём от точки, где лицо реально оказалось на экране.
        val origin = when {
            !kenBurns -> TransformOrigin.Center
            crop != null -> TransformOrigin(crop.screenX, crop.screenY)
            portrait -> TransformOrigin(0.5f, 0.3f)
            else -> TransformOrigin.Center
        }

        // Наезд к лицу на вписанном снимке: только горизонтальные, только
        // с лицом, только пока не включено ручное увеличение.
        val fitPlan = remember(photo.id, boxW, boxH, imageW, imageH, focusX, focusY, cropped, hasFace, fitFaceZoom, manual, portrait) {
            if (fitFaceZoom && zoomStrength > 1.001f && !cropped && hasFace && knownSize &&
                !portrait && !manual && boxW > 0f && boxH > 0f
            ) {
                fitZoomPlan(boxW, boxH, imageW, imageH, focusX, focusY)
            } else {
                null
            }
        }

        // Запуск анимации привязан к конкретному кадру: меняется slide —
        // масштаб сбрасывается в исходный и едет заново.
        var started by remember(photo.id) { mutableStateOf(false) }

        val fitProgress by animateFloatAsState(
            targetValue = if (started && fitPlan != null) 1f else 0f,
            animationSpec = tween(
                durationMillis = if (fitPlan != null) intervalMillis + 2000 else 0,
                easing = LinearEasing
            ),
            label = "fitzoom"
        )
        val scale by animateFloatAsState(
            targetValue = if (started) zoomStrength else 1f,
            animationSpec = tween(
                durationMillis = if (kenBurns) intervalMillis + 2000 else 0,
                easing = LinearEasing
            ),
            label = "kenburns"
        )
        LaunchedEffect(photo.id) { started = true }

        AsyncImage(
            model = url,
            contentDescription = photo.displayName,
            contentScale = if (cropped) ContentScale.Crop else ContentScale.Fit,
            alignment = alignment,
            onSuccess = { state ->
                // Размеры пригождаются и для кадрирования, и как замена
                // нулям в индексе у снимков с сетевой папки.
                val w = state.result.drawable.intrinsicWidth
                val h = state.result.drawable.intrinsicHeight
                if (w > 0 && h > 0) {
                    imageW = w.toFloat()
                    imageH = h.toFloat()
                }
                onLoadSuccess()
            },
            onError = { onLoadError() },
            modifier = when {
                manual -> Modifier.fillMaxSize().manualZoom(zoom, panX, panY)
                kenBurns -> Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = origin
                    }
                fitPlan != null -> Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val s = 1f + (fitPlan.scale - 1f) * fitProgress
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin.Center
                        translationX = fitPlan.translationX * fitProgress
                        translationY = fitPlan.translationY * fitProgress
                    }
                else -> Modifier.fillMaxSize()
            }
        )
    }
}

/**
 * Куда сдвинуть картинку и где после этого окажется точка интереса.
 *
 * @param biasX смещение для BiasAlignment по горизонтали, -1..1
 * @param biasY то же по вертикали
 * @param screenX доля ширины экрана, где оказалась точка интереса
 * @param screenY доля высоты экрана, где оказалась точка интереса
 */
internal data class CropPlacement(
    val biasX: Float,
    val biasY: Float,
    val screenX: Float,
    val screenY: Float
)

/**
 * Пересчёт «точка в долях снимка» → «сдвиг картинки на экране».
 *
 * ContentScale.Crop растягивает снимок так, чтобы он закрыл экран целиком,
 * и по одной из сторон он выступает за край. Смещением можно выбрать, какая
 * часть выступа срежется сверху, а какая снизу.
 */
internal fun cropPlacement(
    boxW: Float,
    boxH: Float,
    imageW: Float,
    imageH: Float,
    focusX: Float,
    focusY: Float
): CropPlacement {
    val scale = maxOf(boxW / imageW, boxH / imageH)
    val scaledW = imageW * scale
    val scaledH = imageH * scale

    val x = axis(boxW, scaledW, focusX)
    val y = axis(boxH, scaledH, focusY)

    return CropPlacement(biasX = x.first, biasY = y.first, screenX = x.second, screenY = y.second)
}

/**
 * Одна ось. Возвращает пару: смещение для BiasAlignment и доля экрана,
 * в которой в итоге оказалась точка интереса.
 *
 * Compose ставит картинку так: offset = (экран − картинка) / 2 × (1 + bias).
 * Чтобы точка f попала в середину экрана, нужно
 * bias = (2f − 1) × картинка / выступ. Множитель тут и есть та поправка,
 * без которой смещение выходило в разы меньше нужного.
 *
 * Когда вычисленное смещение упирается в предел (лицо у самого края
 * снимка), точка интереса в центр не встаёт — поэтому её фактическое
 * положение считается обратным ходом и отдаётся наружу для наезда.
 */
internal fun axis(box: Float, scaled: Float, focus: Float): Pair<Float, Float> {
    val overflow = scaled - box
    if (overflow <= 0.5f) {
        // По этой стороне картинка ровно по экрану — двигать нечего.
        return 0f to focus.coerceIn(0f, 1f)
    }

    val bias = ((2f * focus - 1f) * scaled / overflow).coerceIn(-1f, 1f)
    val offset = -overflow / 2f * (1f + bias)
    val onScreen = ((focus * scaled + offset) / box).coerceIn(0f, 1f)
    return bias to onScreen
}

/**
 * Конечное положение наезда на вписанном снимке.
 *
 * @param scale во сколько раз увеличить (от центра)
 * @param translationX сдвиг в пикселях после увеличения, чтобы лицо
 *   подошло к середине экрана, не открыв щель по краю
 */
internal data class FitZoom(
    val scale: Float,
    val translationX: Float,
    val translationY: Float
)

/**
 * ContentScale.Fit ставит картинку по центру, уменьшив её до размера
 * экрана по большей стороне; по другой остаются поля. Увеличение до
 * max(экран / картинка) по обеим осям убирает поля; выше [FIT_ZOOM_MAX]
 * не идём. После увеличения картинка выступает за экран по одной оси,
 * и в пределах этого выступа лицо сдвигается к середине.
 */
internal fun fitZoomPlan(
    boxW: Float,
    boxH: Float,
    imageW: Float,
    imageH: Float,
    focusX: Float,
    focusY: Float
): FitZoom {
    val fit = minOf(boxW / imageW, boxH / imageH)
    val shownW = imageW * fit
    val shownH = imageH * fit

    val scale = maxOf(boxW / shownW, boxH / shownH).coerceIn(1f, FIT_ZOOM_MAX)

    val zoomedW = shownW * scale
    val zoomedH = shownH * scale

    // Где лицо после увеличения от центра, и на сколько его можно
    // подвинуть к середине, не оголив край.
    fun shift(box: Float, zoomed: Float, focus: Float): Float {
        val overflow = zoomed - box
        if (overflow <= 0.5f) return 0f
        val facePos = box / 2f + (focus - 0.5f) * zoomed
        return (box / 2f - facePos).coerceIn(-overflow / 2f, overflow / 2f)
    }

    return FitZoom(
        scale = scale,
        translationX = shift(boxW, zoomedW, focusX),
        translationY = shift(boxH, zoomedH, focusY)
    )
}

/** Предел наезда на вписанном снимке: дальше узкие кадры расплываются. */
internal const val FIT_ZOOM_MAX = 1.35f

/** Белая полоса между снимками пары. */
private val PAIR_SEPARATOR = 8.dp

/** Поля вокруг пары, чтобы кадры не упирались в края экрана. */
private val PAIR_MARGIN = 24.dp
