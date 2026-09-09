package com.fotoframe.engine

import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Дата съёмки из имени файла.
 *
 * Телефоны и программы резервного копирования почти всегда пишут момент
 * съёмки прямо в имя: 20260523_165611.heic, IMG_20190803_154501.jpg,
 * PXL_20220101_093000123.jpg, 2019-08-03 15.45.01.jpg. Для архива в
 * десятки тысяч файлов это единственный способ получить дату сразу:
 * EXIF пришлось бы вычитывать по сети у каждого файла, а это часы.
 *
 * EXIF всё равно остаётся главнее — когда проход до снимка доберётся,
 * дата из заголовка перезапишет разобранную из имени.
 */
object FileNameDate {

    /**
     * Момент съёмки в миллисекундах или null, если в имени даты нет.
     * Время трактуется как местное: телефоны именно так и называют файлы.
     */
    fun parse(name: String): Long? {
        val digits = DIGIT_RUN.findAll(name).map { it.value }.toList()

        for (run in digits) {
            // Слитная запись: 20260523165611 или 20260523_165611 (склеенная).
            if (run.length >= 14) {
                build(
                    run.substring(0, 4), run.substring(4, 6), run.substring(6, 8),
                    run.substring(8, 10), run.substring(10, 12), run.substring(12, 14)
                )?.let { return it }
            }
        }

        // Дата и время отдельными группами: 20190803_154501, 2019-08-03 15.45.01.
        val m = SPLIT.find(name)
        if (m != null) {
            val (y, mo, d, h, mi, s) = m.destructured
            build(y, mo, d, h, mi, s)?.let { return it }
        }

        // Только дата, без времени: 2019-08-03.jpg. Ставим полдень, чтобы
        // сдвиг часового пояса не перебросил снимок на соседние сутки.
        val dOnly = DATE_ONLY.find(name)
        if (dOnly != null) {
            val (y, mo, d) = dOnly.destructured
            build(y, mo, d, "12", "00", "00")?.let { return it }
        }

        // Слитная дата без разделителей: IMG-20240501-WA0001.jpg. Проверки
        // года, месяца и дня отсекают случайные восьмизначные числа.
        for (run in digits) {
            if (run.length == 8) {
                build(
                    run.substring(0, 4), run.substring(4, 6), run.substring(6, 8),
                    "12", "00", "00"
                )?.let { return it }
            }
        }

        return null
    }

    private fun build(
        y: String, mo: String, d: String,
        h: String, mi: String, s: String
    ): Long? {
        val year = y.toIntOrNull() ?: return null
        val month = mo.toIntOrNull() ?: return null
        val day = d.toIntOrNull() ?: return null
        val hour = h.toIntOrNull() ?: return null
        val minute = mi.toIntOrNull() ?: return null
        val second = s.toIntOrNull() ?: return null

        // Отсекаем случайные числа в именах: телефонные номера, размеры,
        // идентификаторы. Цифровая фотография началась заметно позже 1990.
        if (year < 1990 || year > 2100) return null
        if (month !in 1..12 || day !in 1..31) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null

        val cal = GregorianCalendar().apply {
            isLenient = false
            clear()
            set(year, month - 1, day, hour, minute, second)
        }

        val millis = runCatching { cal.timeInMillis }.getOrNull() ?: return null

        // Дата из будущего — почти наверняка не дата съёмки.
        if (millis > System.currentTimeMillis() + DAY) return null
        return millis
    }

    private const val DAY = 24L * 60 * 60 * 1000

    private val DIGIT_RUN = Regex("\\d+")

    private val SPLIT = Regex(
        "(\\d{4})[-_.]?(\\d{2})[-_.]?(\\d{2})[ _T-]+(\\d{2})[-_.:]?(\\d{2})[-_.:]?(\\d{2})"
    )

    private val DATE_ONLY = Regex("(\\d{4})[-_.](\\d{2})[-_.](\\d{2})")

    private operator fun MatchResult.Destructured.component6(): String = match.groupValues[6]
}
