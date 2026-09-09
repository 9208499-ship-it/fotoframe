package com.fotoframe.engine

import kotlinx.coroutines.CancellationException

/**
 * Отмена корутины — это исключение, и обычный `catch (e: Throwable)` или
 * `runCatching` его проглатывает. Последствия неприятные: таймаут при
 * подготовке кадра превращался в «не удалось подключиться» на экране,
 * закрывал живое соединение с хранилищем и засчитывался снимку как
 * неудачная попытка. Поэтому в каждом перехвате «всего подряд» первым
 * делом отмена пробрасывается дальше.
 */
fun Throwable.rethrowIfCancelled() {
    if (this is CancellationException) throw this
}

/**
 * То же, что `runCatching { }.getOrNull()`, но отмену не глотает.
 * Для suspend-блоков это единственный безопасный вариант.
 */
suspend inline fun <T> tryOrNull(block: () -> T): T? =
    try {
        block()
    } catch (e: Throwable) {
        e.rethrowIfCancelled()
        null
    }
