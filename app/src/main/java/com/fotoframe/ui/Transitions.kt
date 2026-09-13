package com.fotoframe.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import com.fotoframe.data.prefs.Transition

/**
 * Пары «вход / выход» для смены кадра.
 *
 * Переходы намеренно медленные и без резких движений: рамка висит на стене
 * и попадает в поле зрения боковым зрением, поэтому быстрая анимация
 * читается как мелькание. Длительность настраивается отдельно от интервала.
 *
 * Режим «чередовать все» разыгрывается через [concrete] один раз на смену
 * кадра, и уже конкретный эффект передаётся сюда. Раньше вход и выход
 * разыгрывались независимо, и новый кадр мог въезжать сбоку, пока старый
 * уезжал вверх.
 */
object Transitions {

    /** RANDOM раскрывается в конкретный эффект — один раз на смену кадра. */
    fun concrete(transition: Transition): Transition =
        if (transition == Transition.RANDOM) REAL_EFFECTS.random() else transition

    fun enterFor(transition: Transition, durationMillis: Int): EnterTransition =
        when (transition) {
            Transition.CROSSFADE -> fadeIn(tween(durationMillis, easing = LinearEasing))

            Transition.SLIDE -> fadeIn(tween(durationMillis / 2)) +
                slideInHorizontally(
                    animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
                    initialOffsetX = { it / 6 }
                )

            Transition.ZOOM_BLUR -> fadeIn(tween(durationMillis)) +
                scaleIn(
                    animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
                    initialScale = 1.08f
                )

            Transition.PUSH_UP -> fadeIn(tween(durationMillis / 2)) +
                slideInVertically(
                    animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
                    initialOffsetY = { it / 8 }
                )

            // Куб, круг и шторка рисуются модификатором в TransitionEffects:
            // здесь фаза перехода нужна почти мгновенная, иначе кадр
            // просвечивает и эффект теряется.
            Transition.CUBE, Transition.CIRCLE, Transition.WIPE ->
                fadeIn(tween(FLASH_SNAP_MS))

            Transition.FLASH -> fadeIn(tween(durationMillis / 2, delayMillis = durationMillis / 2))

            else -> fadeIn(tween(durationMillis))
        }

    fun exitFor(transition: Transition, durationMillis: Int): ExitTransition =
        when (transition) {
            Transition.CROSSFADE -> fadeOut(tween(durationMillis, easing = LinearEasing))

            Transition.SLIDE -> fadeOut(tween(durationMillis)) +
                slideOutHorizontally(
                    animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
                    targetOffsetX = { -it / 6 }
                )

            Transition.ZOOM_BLUR -> fadeOut(tween(durationMillis)) +
                scaleOut(
                    animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
                    targetScale = 0.96f
                )

            Transition.PUSH_UP -> fadeOut(tween(durationMillis)) +
                slideOutVertically(
                    animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
                    targetOffsetY = { -it / 8 }
                )

            Transition.CUBE -> fadeOut(tween(durationMillis, easing = LinearEasing))

            // Уходящий кадр остаётся на месте, пока его закрывает новый.
            Transition.CIRCLE, Transition.WIPE ->
                fadeOut(tween(FLASH_SNAP_MS, delayMillis = durationMillis))

            Transition.FLASH -> fadeOut(tween(durationMillis / 2))

            else -> fadeOut(tween(durationMillis))
        }

    /** Мгновенная фаза: эффект рисует не прозрачность, а сам модификатор. */
    private const val FLASH_SNAP_MS = 1

    private val REAL_EFFECTS = listOf(
        Transition.CROSSFADE,
        Transition.SLIDE,
        Transition.ZOOM_BLUR,
        Transition.PUSH_UP,
        Transition.CUBE,
        Transition.CIRCLE,
        Transition.WIPE,
        Transition.FLASH
    )

    fun label(t: Transition): String = when (t) {
        Transition.CROSSFADE -> "Растворение"
        Transition.SLIDE -> "Сдвиг вбок"
        Transition.ZOOM_BLUR -> "Наплыв"
        Transition.PUSH_UP -> "Сдвиг вверх"
        Transition.CUBE -> "Куб"
        Transition.CIRCLE -> "Круг"
        Transition.WIPE -> "Шторка"
        Transition.FLASH -> "Вспышка"
        Transition.RANDOM -> "Чередовать все"
    }
}
