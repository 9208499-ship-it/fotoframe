package com.fotoframe.engine

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Две дешёвые оценки картинки, обе на её крошечной копии.
 *
 * Перцептивный хэш ([dHash]) — 64 бита, описывающие, где яркость растёт
 * слева направо, на сетке 9×8. Почти одинаковые кадры (серия с одной
 * точки, дубль с чуть другой экспозицией) дают хэши, отличающиеся на
 * несколько бит; разные — на десятки. Это классический dHash, он
 * устойчив к сжатию, лёгкому масштабированию и небольшой обрезке.
 *
 * Заметность ([salientFocus]) — где на снимке «что-то есть». Настоящая
 * заметность — нейросеть; здесь эвристика, которой пользуется, например,
 * smartcrop: резкость краёв плюс насыщенность цвета по клеткам сетки, с
 * небольшим перевесом центру. Горизонт, здание, цветок на фоне неба она
 * находит уверенно; на снимке с равномерной текстурой ошибётся, но там
 * и наезжать особо некуда.
 */
object ImageSignals {

    /** Яркость и насыщенность в клетках сетки, посчитанные из битмапа. */
    class Grid(val w: Int, val h: Int, val luma: FloatArray, val sat: FloatArray)

    /** Уменьшить битмап до сетки [w]×[h] и посчитать яркость и насыщенность. */
    fun gridOf(bitmap: Bitmap, w: Int, h: Int): Grid {
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()

        val luma = FloatArray(w * h)
        val sat = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16 and 0xFF) / 255f
            val g = (p shr 8 and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            sat[i] = if (mx > 0f) (mx - mn) / mx else 0f
        }
        return Grid(w, h, luma, sat)
    }

    /**
     * dHash: сетка 9×8, бит = «правый сосед ярче левого». Порядок битов —
     * по строкам, старший бит первый.
     */
    fun dHash(bitmap: Bitmap): Long {
        val g = gridOf(bitmap, 9, 8)
        return dHash(g)
    }

    fun dHash(g: Grid): Long {
        require(g.w == 9 && g.h == 8) { "dHash ждёт сетку 9×8" }
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = g.luma[y * 9 + x]
                val right = g.luma[y * 9 + x + 1]
                hash = (hash shl 1) or (if (right > left) 1L else 0L)
            }
        }
        return hash
    }

    /** Сколько бит различаются. 0 — одинаковые, ~32 — не связаны. */
    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * Центр интересной области в долях кадра (0..1), или null, если
     * выделить нечего — снимок равномерный, и наезд от центра ничем не
     * хуже.
     *
     * Оценка клетки = резкость (перепад яркости с соседями) + насыщенность,
     * умноженная на перевес к центру. Берутся клетки выше медианы, и по
     * ним считается центр масс. Итог слегка притянут к центру: наезд на
     * самый край кадра выглядит как ошибка, даже если там и правда самое
     * интересное.
     */
    fun salientFocus(g: Grid): Pair<Float, Float>? {
        val w = g.w
        val h = g.h
        if (w < 4 || h < 4) return null

        val score = FloatArray(w * h)
        var total = 0f
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val c = g.luma[i]
                val edge = abs(c - g.luma[i - 1]) + abs(c - g.luma[i + 1]) +
                    abs(c - g.luma[i - w]) + abs(c - g.luma[i + w])
                // Перевес центру: 1 в середине, ~0.5 у краёв.
                val dx = (x + 0.5f) / w - 0.5f
                val dy = (y + 0.5f) / h - 0.5f
                val center = 1f - 0.5f * (dx * dx + dy * dy) * 4f
                val v = (edge * EDGE_WEIGHT + g.sat[i] * SAT_WEIGHT) * center
                score[i] = v
                total += v
            }
        }
        if (total <= 0f) return null

        // Порог — медиана ненулевых оценок: интересное — то, что выше
        // среднего, а не сколько-то лучших клеток фиксированным числом.
        val sorted = score.filter { it > 0f }.sorted()
        if (sorted.size < 8) return null
        val threshold = sorted[sorted.size / 2]

        var sx = 0f
        var sy = 0f
        var sw = 0f
        var peak = 0f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = score[y * w + x]
                if (v <= threshold) continue
                sx += (x + 0.5f) * v
                sy += (y + 0.5f) * v
                sw += v
                if (v > peak) peak = v
            }
        }
        if (sw <= 0f) return null

        // Равномерный снимок: лучшая клетка почти не выделяется на фоне
        // порога — центр масс тут случаен, честнее вернуть «ничего».
        if (peak < threshold * FLATNESS_RATIO) return null

        val fx = (sx / sw) / w
        val fy = (sy / sw) / h
        // Притяжение к центру на четверть пути.
        return Pair(
            (0.5f + (fx - 0.5f) * 0.75f).coerceIn(0.15f, 0.85f),
            (0.5f + (fy - 0.5f) * 0.75f).coerceIn(0.15f, 0.85f)
        )
    }

    /** Сетка для заметности: достаточно мелкая, чтобы видеть форму, и дешёвая. */
    const val SALIENCY_W = 32
    const val SALIENCY_H = 18

    private const val EDGE_WEIGHT = 1f
    private const val SAT_WEIGHT = 0.35f
    private const val FLATNESS_RATIO = 1.8f
}
