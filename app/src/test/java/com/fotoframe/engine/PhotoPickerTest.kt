package com.fotoframe.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoPickerTest {

    @Test
    fun noBiasMeansHalfOfCollection() {
        assertEquals(0.5, PhotoPicker.medianShare(1f), 1e-9)
    }

    @Test
    fun biasThreeIsTwelveAndAHalfPercent() {
        // Значение из README: при bias = 3 половина показов — свежие 12 %.
        assertEquals(0.125, PhotoPicker.medianShare(3f), 1e-9)
    }

    @Test
    fun biasFiveIsAboutThreePercent() {
        assertEquals(0.03125, PhotoPicker.medianShare(5f), 1e-9)
    }

    @Test
    fun medianMatchesSampling() {
        // Проверка формулы по-честному: доля позиций u^bias ниже medianShare
        // должна быть близка к половине.
        val bias = 3.0
        val threshold = PhotoPicker.medianShare(bias.toFloat())
        val rnd = java.util.Random(42)
        var below = 0
        val n = 200_000
        repeat(n) { if (Math.pow(rnd.nextDouble(), bias) < threshold) below++ }
        assertEquals(0.5, below.toDouble() / n, 0.01)
    }
}
