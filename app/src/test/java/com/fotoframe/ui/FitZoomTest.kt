package com.fotoframe.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Наезд на лицо для вписанного снимка. Экран 1920×1080. */
class FitZoomTest {

    @Test
    fun fourByThreeFillsWidthAndCentersFaceVertically() {
        // 4:3 вписан по высоте: 1440×1080, поля по 240 слева и справа.
        val z = fitZoomPlan(1920f, 1080f, 4000f, 3000f, 0.5f, 0.3f)
        assertEquals(1920f / 1440f, z.scale, 1e-4f)
        // По ширине выступа нет — сдвигать нечего.
        assertEquals(0f, z.translationX, 1e-4f)
        // По высоте выступ 360, лицо на 30 % — хотим сдвинуть на 288,
        // можно только на 180.
        assertEquals(180f, z.translationY, 1e-3f)
    }

    @Test
    fun panoramaIsCappedAndFillsHeightInstead() {
        // 3:1 вписан по ширине: 1920×640, поля сверху и снизу по 220.
        // Чтобы убрать их, нужен ×1,69 — выше потолка.
        val z = fitZoomPlan(1920f, 1080f, 3000f, 1000f, 0.5f, 0.5f)
        assertEquals(FIT_ZOOM_MAX, z.scale, 1e-4f)
        assertTrue(z.scale * 640f < 1080f) // поля останутся, но уже
    }

    @Test
    fun screenShapedPhotoDoesNotMove() {
        val z = fitZoomPlan(1920f, 1080f, 1920f, 1080f, 0.2f, 0.2f)
        assertEquals(1f, z.scale, 1e-6f)
        assertEquals(0f, z.translationX, 1e-6f)
        assertEquals(0f, z.translationY, 1e-6f)
    }

    @Test
    fun faceNearEdgeNeverOpensAGap() {
        val z = fitZoomPlan(1920f, 1080f, 4000f, 3000f, 0.5f, 0.02f)
        val zoomedH = 1080f * z.scale
        assertTrue(z.translationY <= (zoomedH - 1080f) / 2f + 1e-3f)
    }
}
