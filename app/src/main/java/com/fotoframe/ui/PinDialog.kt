package com.fotoframe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ввод ПИН-кода перед настройками. Цифровая панель на экране — её
 * проходят стрелками пульта и нажимают пальцем; цифровые кнопки пульта,
 * если они есть, тоже работают.
 */
@Composable
fun PinDialog(
    pin: String,
    onSuccess: () -> Unit,
    onCancel: () -> Unit
) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }

    fun type(d: Char) {
        wrong = false
        if (entered.length >= 8) return
        entered += d
        if (entered.length == pin.length) {
            if (entered == pin) onSuccess() else {
                wrong = true
                entered = ""
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { firstKey.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val digit = DIGIT_KEYS[e.key]
                when {
                    digit != null -> { type(digit); true }
                    e.key == Key.Back || e.key == Key.Escape -> { onCancel(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .background(Color(0xFF1B1E22), RoundedCornerShape(16.dp))
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Код для настроек", color = Color.White, fontSize = 26.sp)
            Text(
                if (wrong) "Неверный код" else "•".repeat(entered.length).ifEmpty { " " },
                color = if (wrong) Color(0xFFFF8A80) else Color.White,
                fontSize = 30.sp
            )
            val rows = listOf("123", "456", "789")
            rows.forEachIndexed { r, digits ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    digits.forEachIndexed { c, d ->
                        val mod = if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier
                        Button(onClick = { type(d) }, modifier = mod.size(72.dp)) {
                            Text(d.toString(), fontSize = 24.sp)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onCancel, modifier = Modifier.size(72.dp)) { Text("✕", fontSize = 22.sp) }
                Button(onClick = { type('0') }, modifier = Modifier.size(72.dp)) { Text("0", fontSize = 24.sp) }
                Button(
                    onClick = { wrong = false; entered = entered.dropLast(1) },
                    modifier = Modifier.size(72.dp)
                ) { Text("⌫", fontSize = 22.sp) }
            }
        }
    }
}

private val DIGIT_KEYS = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9'
)
