package com.fotoframe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

class FileNameDateTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0, s: Int = 0): Long =
        GregorianCalendar().apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
        }.timeInMillis

    @Test
    fun samsungStyle() {
        assertEquals(at(2026, 5, 23, 16, 56, 11), FileNameDate.parse("20260523_165611.heic"))
    }

    @Test
    fun imgPrefix() {
        assertEquals(at(2019, 8, 3, 15, 45, 1), FileNameDate.parse("IMG_20190803_154501.jpg"))
    }

    @Test
    fun pixelWithMillis() {
        assertEquals(at(2022, 1, 1, 9, 30, 0), FileNameDate.parse("PXL_20220101_093000123.jpg"))
    }

    @Test
    fun dashesAndDots() {
        assertEquals(at(2019, 8, 3, 15, 45, 1), FileNameDate.parse("2019-08-03 15.45.01.jpg"))
    }

    @Test
    fun dateOnlyIsNoon() {
        assertEquals(at(2019, 8, 3), FileNameDate.parse("2019-08-03.jpg"))
    }

    @Test
    fun whatsappDateOnly() {
        assertEquals(at(2024, 5, 1), FileNameDate.parse("IMG-20240501-WA0001.jpg"))
    }

    @Test
    fun cameraCounterIsNotADate() {
        assertNull(FileNameDate.parse("DSC_0123.JPG"))
        assertNull(FileNameDate.parse("Photo_0123.jpg"))
    }

    @Test
    fun phoneNumberIsNotADate() {
        assertNull(FileNameDate.parse("79161234567.jpg"))
    }

    @Test
    fun futureIsRejected() {
        val next = GregorianCalendar().get(Calendar.YEAR) + 2
        assertNull(FileNameDate.parse("${next}0101_120000.jpg"))
    }

    @Test
    fun invalidMonthIsRejected() {
        assertNull(FileNameDate.parse("20251301_120000.jpg"))
        assertNotNull(FileNameDate.parse("20251201_120000.jpg"))
    }
}
