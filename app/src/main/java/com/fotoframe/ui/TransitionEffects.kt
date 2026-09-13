package com.fotoframe.ui

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.fotoframe.data.prefs.Transition
import kotlin.math.hypot

/**
 * Эффекты смены кадра, которые нельзя выразить парой «вход / выход».
 *
 * Растворение, сдвиг и наплыв целиком описываются готовыми EnterTransition
 * и ExitTransition. А поворот куба, раскрытие кругом и шторка требуют
 * доступа к самому содержимому: их делает этот модификатор, который
 * навешивается на каждый кадр внутри AnimatedContent и читает ход
 * анимации у своего элемента.
 *
 * Всё построено на повороте и обрезке слоя — это делает видеоускоритель,
 * и на приставке такие переходы идут ровно. Попиксельных эффектов
 * («ветер», «звёзды») здесь намеренно нет: на слабом железе они дёргаются
 * сильнее, чем украшают.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedVisibilityScope.transitionEffect(
    transition: Transition,
    durationMillis: Int
): Modifier {
    if (transition !in EFFECTS) return Modifier

    // Для входящего кадра ход идёт 0 → 1, для уходящего 1 → 0.
    val progress by this.transition.animateFloat(
        transitionSpec = {
            tween(
                durationMillis,
                easing = if (transition == Transition.FLASH) LinearEasing else FastOutSlowInEasing
            )
        },
        label = "effect"
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    val entering = this.transition.targetState == EnterExitState.Visible

    return when (transition) {
        Transition.CUBE -> Modifier.graphicsLayer {
            // Входящий поворачивается к зрителю от правого края, уходящий
            // отворачивается влево — вместе выглядит как грань куба.
            val angle = (1f - progress) * 90f
            rotationY = if (entering) angle else -angle
            transformOrigin = TransformOrigin(if (entering) 0f else 1f, 0.5f)
            // Без этого перспектива слишком резкая и края «ломаются».
            cameraDistance = 24f * density
        }

        Transition.CIRCLE ->
            if (entering) Modifier.clip(CircleReveal(progress)) else Modifier

        Transition.WIPE ->
            if (entering) Modifier.clip(WipeReveal(progress)) else Modifier

        else -> Modifier
    }
}

/** Нужна ли белая вспышка поверх кадра и насколько она сейчас плотная. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AnimatedVisibilityScope.flashAlpha(transition: Transition, durationMillis: Int): Float {
    if (transition != Transition.FLASH) return 0f

    val progress by this.transition.animateFloat(
        transitionSpec = { tween(durationMillis, easing = LinearEasing) },
        label = "flash"
    ) { state -> if (state == EnterExitState.Visible) 1f else 0f }

    // Ярче всего в середине смены: у входящего кадра в начале, у
    // уходящего в конце — вместе получается одна вспышка, а не две.
    return if (this.transition.targetState == EnterExitState.Visible) {
        (1f - progress).coerceIn(0f, 1f)
    } else {
        (1f - progress).coerceIn(0f, 1f)
    }
}

/** Круг, растущий из центра до полного покрытия экрана. */
private class CircleReveal(private val progress: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val full = hypot(size.width, size.height) / 2f
        val radius = full * progress.coerceIn(0f, 1f)
        val path = Path().apply {
            addOval(Rect(Offset(size.width / 2f, size.height / 2f), radius))
        }
        return Outline.Generic(path)
    }
}

/** Шторка слева направо. */
private class WipeReveal(private val progress: Float) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Rectangle(
        Rect(0f, 0f, size.width * progress.coerceIn(0f, 1f), size.height)
    )
}

private val EFFECTS = setOf(Transition.CUBE, Transition.CIRCLE, Transition.WIPE)
