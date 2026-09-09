package com.fotoframe.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fotoframe.engine.BrowseState

/**
 * Обзор папок источника.
 *
 * Работает так же, как файловый диалог в любой программе: список подпапок,
 * заход внутрь нажатием, кнопка наверх и кнопка «выбрать эту папку». Путь
 * набирать руками не нужно — на пульте это было бы мучением.
 */
@Composable
fun FolderBrowser(
    state: BrowseState,
    onOpen: (com.fotoframe.source.Folder) -> Unit,
    onUp: () -> Unit,
    onChoose: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101214))
            .padding(horizontal = 64.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            state.title,
            color = Color.White,
            fontSize = 30.sp,
            fontWeight = FontWeight.Light
        )

        Text(
            text = when {
                state.path == com.fotoframe.engine.SlideshowViewModel.SHARES_ROOT ->
                    "Общие папки хранилища — откройте нужную"
                state.path.isBlank() -> "Корень"
                else -> state.path
            },
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 16.sp
        )

        if (state.error != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF3A2226), RoundedCornerShape(10.dp))
                    .padding(20.dp)
            ) {
                Text(state.error, color = Color(0xFFFF8A80), fontSize = 16.sp)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onUp) {
                Text(if (state.stack.isEmpty()) "Отмена" else "Наверх")
            }
            Button(onClick = onChoose) { Text("Выбрать эту папку") }
            Button(onClick = onCancel) { Text("Закрыть") }
        }

        if (state.loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = Color(0xFF3D6FE0))
                Text("Читаю список папок…", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
            }
            return@Column
        }

        if (state.entries.isEmpty() && state.error == null) {
            Text(
                "Здесь нет вложенных папок. Если фотографии лежат прямо тут, " +
                    "нажмите «Выбрать эту папку».",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 16.sp
            )
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.entries) { folder ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .focusHighlight(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1B1E22), RoundedCornerShape(10.dp))
                        .clickable { onOpen(folder) }
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    Text(folder.name, color = Color.White, fontSize = 19.sp)
                }
            }
        }
    }
}
