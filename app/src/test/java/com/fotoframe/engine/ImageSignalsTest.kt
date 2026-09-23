package com.fotoframe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSignalsTest {

    private fun grid(w: Int, h: Int, f: (Int, Int) -> Pair<Float, Float>): ImageSignals.Grid {
        val luma = FloatArray(w * h)
        val sat = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val (l, s) = f(x, y)
            luma[y * w + x] = l
            sat[y * w + x] = s
        }
        return ImageSignals.Grid(w, h, luma, sat)
    }

    @Test
    fun hashOfIdenticalGridsIsIdentical() {
        val rnd = java.util.Random(1)
        val g = grid(9, 8) { _, _ -> rnd.nextFloat() to 0f }
        assertEquals(0, ImageSignals.hamming(ImageSignals.dHash(g), ImageSignals.dHash(g)))
    }

    @Test
    fun hashIgnoresBrightnessShift() {
        val rnd = java.util.Random(2)
        val g = grid(9, 8) { _, _ -> rnd.nextFloat() to 0f }
        val darker = ImageSignals.Grid(9, 8, FloatArray(72) { g.luma[it] * 0.7f + 0.1f }, g.sat)
        assertEquals(0, ImageSignals.hamming(ImageSignals.dHash(g), ImageSignals.dHash(darker)))
    }

    @Test
    fun hashOfUnrelatedGridsIsFar() {
        val rnd = java.util.Random(3)
        val a = grid(9, 8) { _, _ -> rnd.nextFloat() to 0f }
        val b = grid(9, 8) { _, _ -> rnd.nextFloat() to 0f }
        assertTrue(ImageSignals.hamming(ImageSignals.dHash(a), ImageSignals.dHash(b)) > 15)
    }

    @Test
    fun saliencyFindsObjectOnTheRight() {
        val g = grid(32, 18) { x, y ->
            if (x in 20..24 && y in 6..11) 0.9f to 0.8f else 0.3f to 0.05f
        }
        val f = ImageSignals.salientFocus(g)
        assertNotNull(f)
        assertTrue(f!!.first > 0.58f)
        assertEquals(0.5f, f.second, 0.05f)
    }

    @Test
    fun saliencyOfFlatImageIsNull() {
        val g = grid(32, 18) { _, _ -> 0.5f to 0.1f }
        assertNull(ImageSignals.salientFocus(g))
    }

    @Test
    fun saliencyStaysAwayFromTheVeryEdge() {
        val g = grid(32, 18) { x, y -> if (x <= 1 && y <= 1) 1f to 1f else 0.2f to 0f }
        val f = ImageSignals.salientFocus(g)
        if (f != null) {
            assertTrue(f.first >= 0.15f)
            assertTrue(f.second >= 0.15f)
        }
    }
}
