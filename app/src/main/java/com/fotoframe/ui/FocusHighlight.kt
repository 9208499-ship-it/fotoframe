package com.fotoframe.ui

import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Рамка вокруг элемента, на котором стоит фокус пульта.
 *
 * Кнопки и переключатели Material 3 подсвечивают фокус сами, а у наших
 * плиток на `Box.clickable` подсветки не было: с D-pad непонятно, на чём
 * стоишь, и выбор варианта превращался в угадывание. Ставится ДО
 * `clickable`, чтобы рамка рисовалась вокруг всей кликабельной области.
 */
fun Modifier.focusHighlight(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .border(
            width = 3.dp,
            color = if (focused) Color.White else Color.Transparent,
            shape = shape
        )
}
