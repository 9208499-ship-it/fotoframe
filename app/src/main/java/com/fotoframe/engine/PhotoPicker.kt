package com.fotoframe.engine

import com.fotoframe.data.db.Photo
import com.fotoframe.data.db.PhotoDao
import com.fotoframe.data.prefs.PhotoOrder
import com.fotoframe.data.prefs.SlideshowSettings
import kotlin.math.pow
import kotlin.random.Random

/**
 * Выбор следующей фотографии.
 *
 * Про приоритет новых. Обычная реализация «случайно, но поновее» сортирует
 * коллекцию по дате и берёт случайный элемент из первых N процентов. На
 * коллекции в двадцать тысяч снимков это означает, что «свежие» — это первые
 * две тысячи, то есть съёмка за последние несколько лет. Приоритет
 * формально есть, а на глаз его нет: показывается что попало.
 *
 * Здесь работают два независимых механизма.
 *
 * 1. Смещение выборки. Позиция в отсортированном по дате списке берётся как
 *    floor(count * u^bias), где u равномерно на [0,1). При bias = 1 это
 *    честный равномерный выбор. При bias = 3 половина показов приходится на
 *    первые 12% коллекции, при bias = 5 — на первые 3%. Ползунок в
 *    настройках меняет ровно этот показатель, и разницу видно сразу.
 *
 * 2. Пул новинок. Снимки, попавшие в индекс за последние N дней и ещё ни разу
 *    не показанные, живут отдельно. С заданной вероятностью очередной кадр
 *    берётся оттуда. Это гарантирует, что вчерашние фотографии с телефона
 *    появятся на рамке сегодня, а не через две недели, — независимо от того,
 *    насколько велика коллекция.
 *
 * Оба механизма работают поверх круга показа: пока не показаны все снимки,
 * повторов нет. Когда круг закрывается, флаги сбрасываются.
 *
 * Выбор здесь только предлагается: пометку «показан» ставит вызывающая
 * сторона в момент фактического вывода на экран. Иначе кадры, подготовленные
 * заранее, но так и не показанные, выпадали бы из круга, а пауза повторов
 * отсчитывалась бы от подготовки, а не от показа.
 */
class PhotoPicker(private val dao: PhotoDao) {

    suspend fun next(settings: SlideshowSettings): Photo? {
        if (dao.countEnabled() == 0) return null

        if (dao.countRemainingInCycle() == 0) {
            dao.resetCycle()
        }

        // Пауза между повторами: снимок, который был на экране недавно,
        // пропускаем. Если под это правило не подходит вообще ничего —
        // показываем без него, иначе рамка встанет.
        val cooldown = if (settings.repeatCooldownDays > 0) {
            val cutoff = System.currentTimeMillis() - settings.repeatCooldownDays * DAY_MS
            if (dao.countAvailable(cutoff) > 0) cutoff else 0L
        } else 0L

        return when (settings.order) {
            PhotoOrder.NEWEST_FIRST -> pickStrictlyNewest(cooldown)
            PhotoOrder.RANDOM -> pickWeighted(bias = 1.0, cooldown = cooldown)
            PhotoOrder.RANDOM_RECENT_FIRST -> pickWithFreshBoost(settings, cooldown)
        }
    }

    /** Строгая хронология: от самых новых к старым, без случайности. */
    private suspend fun pickStrictlyNewest(cooldown: Long): Photo? =
        dao.photoAtRecencyOffset(0, cooldown)

    private suspend fun pickWithFreshBoost(settings: SlideshowSettings, cooldown: Long): Photo? {
        val since = System.currentTimeMillis() - settings.freshWindowDays * DAY_MS
        val freshCount = dao.countFresh(since)

        if (freshCount > 0 && Random.nextFloat() < settings.freshBoost) {
            val offset = Random.nextInt(freshCount)
            dao.freshAt(since, offset)?.let { return it }
        }

        return pickWeighted(settings.recencyBias.toDouble(), cooldown)
    }

    /**
     * Взвешенный выбор по дате съёмки.
     *
     * @param bias 1.0 — равномерно, больше — сильнее перекос к новым.
     */
    private suspend fun pickWeighted(bias: Double, cooldown: Long): Photo? {
        val remaining = if (cooldown > 0) dao.countAvailable(cooldown)
                        else dao.countRemainingInCycle()
        if (remaining == 0) return null

        val u = Random.nextDouble()
        val offset = (remaining * u.pow(bias)).toInt().coerceIn(0, remaining - 1)

        // Запрос может вернуть null, если другой поток успел изменить набор.
        // Тогда откатываемся к первой доступной записи.
        return dao.photoAtRecencyOffset(offset, cooldown)
            ?: dao.photoAtRecencyOffset(0, cooldown)
            ?: dao.photoAtRecencyOffset(0, 0)
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        /**
         * Доля коллекции, на которую придётся половина показов
         * при заданном смещении. Настройки показывают это число словами,
         * чтобы ползунок был понятен без объяснений.
         *
         * Позиция берётся как u^bias, значит половина значений u (те, что
         * меньше 0,5) даёт позиции меньше 0,5^bias. Раньше здесь стояло
         * 0,5^(1/bias), и подпись обещала «свежие 79 %» вместо 12,5 %.
         */
        fun medianShare(bias: Float): Double = 0.5.pow(bias.toDouble())
    }
}
