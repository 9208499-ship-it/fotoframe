package com.fotoframe.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class PrecipHintTest {

    private val hour = 60L * 60 * 1000

    /** Сегодня, [h]:00 по местному времени. */
    private fun at(h: Int, dayShift: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, dayShift)
        add(Calendar.HOUR_OF_DAY, h)
    }.timeInMillis

    private fun series(from: Int, kinds: List<String?>) =
        kinds.mapIndexed { i, k -> HourPrecip(at(from + i), k) }

    @Test
    fun rainStartsLater() {
        val h = series(9, listOf(null, null, null, null, null, null, "дождь", "дождь"))
        assertEquals("дождь с 15:00", computePrecipHint(h, at(9) + 10 * 60_000))
    }

    @Test
    fun rainStopsLater() {
        val h = series(9, listOf("дождь", "дождь", null, null))
        assertEquals("дождь до 11:00", computePrecipHint(h, at(9) + 10 * 60_000))
    }

    @Test
    fun dryAllWindowMeansNoHint() {
        val h = series(9, List(13) { null })
        assertNull(computePrecipHint(h, at(9)))
    }

    @Test
    fun rainAllWindowMeansNoHint() {
        // Льёт весь горизонт — это уже сказано в строке состояния.
        val h = series(9, List(13) { "дождь" })
        assertNull(computePrecipHint(h, at(9)))
    }

    @Test
    fun tomorrowIsSaidAsTomorrow() {
        val h = listOf(HourPrecip(at(22), null), HourPrecip(at(23), null), HourPrecip(at(6, 1), "снег"))
        assertEquals("снег завтра с 06:00", computePrecipHint(h, at(22)))
    }

    @Test
    fun pastHoursAreIgnored() {
        val h = listOf(HourPrecip(at(9) - 5 * hour, "дождь"), HourPrecip(at(9), null), HourPrecip(at(10), null))
        assertNull(computePrecipHint(h, at(9)))
    }
}
