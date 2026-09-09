package com.fotoframe.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Арифметика смещения кадрирования. Экран 1920×1080, вертикальный снимок
 * 3000×4000: по ширине он растягивается до экрана, по высоте выступает.
 */
class CropPlacementTest {

    private val boxW = 1920f
    private val boxH = 1080f
    private val imgW = 3000f
    private val imgH = 4000f

    @Test
    fun faceInCenterStaysCentered() {
        val c = cropPlacement(boxW, boxH, imgW, imgH, 0.5f, 0.5f)
        assertEquals(0f, c.biasX, 1e-4f)
        assertEquals(0f, c.biasY, 1e-4f)
        assertEquals(0.5f, c.screenY, 1e-3f)
    }

    @Test
    fun faceInUpperPartMovesToScreenCenter() {
        // Лицо на 30 % высоты снимка. Выступ большой, значит смещения
        // хватает, чтобы поставить его ровно в середину экрана.
        val c = cropPlacement(boxW, boxH, imgW, imgH, 0.5f, 0.3f)
        assertEquals(0.5f, c.screenY, 1e-3f)
    }

    @Test
    fun faceAtVeryTopHitsTheLimit() {
        // Лицо у самой кромки: сдвиг упирается в -1, и точка остаётся
        // выше центра — наезд должен идти от её настоящего положения.
        val c = cropPlacement(boxW, boxH, imgW, imgH, 0.5f, 0.02f)
        assertEquals(-1f, c.biasY, 1e-4f)
        assertEquals(true, c.screenY < 0.5f)
    }

    @Test
    fun axisWithoutOverflowDoesNotMove() {
        val (bias, onScreen) = axis(1920f, 1920f, 0.3f)
        assertEquals(0f, bias, 1e-6f)
        assertEquals(0.3f, onScreen, 1e-6f)
    }
}
