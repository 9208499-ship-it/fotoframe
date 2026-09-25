package com.fotoframe.engine

import com.fotoframe.data.db.FilterRow
import com.fotoframe.data.db.PhotoDao
import com.fotoframe.data.prefs.SlideshowSettings

/**
 * Что допускать к показу.
 *
 * Фильтр не удаляет записи, а переставляет флаг enabled — поэтому настройки
 * можно крутить сколько угодно, не переиндексируя коллекцию. Размеры и вес
 * проверяются только если источник их сообщил: неизвестное не отсекается.
 *
 * Решение принимается в Kotlin, а не в SQL, по двум причинам. lower() в
 * SQLite на Android опускает регистр только у латиницы, и «Снимки экрана»
 * никогда не совпадали с «снимки экрана». А в LIKE символ подчёркивания —
 * шаблон любого одного символа, и маска photo_%_% отсеивала всё, что
 * начинается на photo — включая обычные Photo_0123.jpg с камеры.
 */
class ContentFilter(private val dao: PhotoDao) {

    data class Outcome(val enabled: Int, val hidden: Int)

    suspend fun apply(s: SlideshowSettings): Outcome {
        val rows = dao.filterRows()
        val minPixels = (s.minMegapixels * 1_000_000).toLong()
        val minBytes = s.minFileSizeKb * 1024L

        // Снимки источника, который сейчас не настроен, в показ не идут.
        // Записи остаются: убрал токен на время — они спрятались, вернул —
        // вернулись без переиндексации. Раньше они оставались в выборке,
        // не загружались и давали пропуски и чёрные паузы.
        val active = activeSources(s)

        val enable = ArrayList<Long>()
        val disable = ArrayList<Long>()
        for (r in rows) {
            // Картины фильтр содержимого не касается: он про скриншоты,
            // мелкие файлы и служебные папки — у музея таких нет, а размеров
            // до первого показа неизвестно.
            val museum = r.sourceId in com.fotoframe.source.MUSEUM_SOURCES
            val ok = r.sourceId in active && (museum || allowed(r, s, minPixels, minBytes))
            if (ok) enable += r.id else disable += r.id
        }

        // По 500 за раз: у SQLite ограничение на число параметров в запросе.
        enable.chunked(CHUNK).forEach { dao.setEnabledByIds(it, true) }
        disable.chunked(CHUNK).forEach { dao.setEnabledByIds(it, false) }

        return Outcome(enabled = enable.size, hidden = disable.size)
    }

    /** Какие источники настроены. Устройство считается настроенным всегда. */
    private fun activeSources(s: SlideshowSettings): Set<String> = buildSet {
        // Фотографии и картины — разные режимы, не смешиваются.
        if (s.showMode == "art") {
            if (s.artMet) add("met")
            if (s.artCleveland) add("cleveland")
        } else {
            add("local")
            if (!s.yandexToken.isNullOrBlank()) add("yandex")
            if (s.smbHost.isNotBlank() && s.smbShare.isNotBlank()) add("smb")
        }
    }

    private fun allowed(r: FilterRow, s: SlideshowSettings, minPixels: Long, minBytes: Long): Boolean {
        val name = r.displayName.trim().lowercase()
        val album = r.albumName?.trim()?.lowercase()

        if (s.skipJunkFolders) {
            if (album != null && album in JUNK_ALBUMS) return false
            if (JUNK_NAMES.any { it.containsMatchIn(name) }) return false
        }

        if (s.skipPng && GRAPHIC_EXT.any { name.endsWith(it) }) return false

        val w = r.width
        val h = r.height
        if (minPixels > 0 && w > 0 && h > 0 && w.toLong() * h < minPixels) return false
        if (minBytes > 0 && r.sizeBytes > 0 && r.sizeBytes < minBytes) return false

        if (s.maxAspectRatio > 1f && w > 0 && h > 0) {
            val aspect = maxOf(w, h).toFloat() / minOf(w, h)
            if (aspect > s.maxAspectRatio) return false
        }

        return true
    }

    private companion object {
        const val CHUNK = 500

        val JUNK_ALBUMS = setOf(
            "screenshots", "screenshot", "download", "downloads",
            "telegram", "telegram images", "whatsapp", "whatsapp images",
            "viber", "bluetooth", "documents",
            "снимки экрана", "скриншоты", "загрузки", "документы"
        )

        val JUNK_NAMES = listOf(
            Regex("^screenshot"),
            Regex("^screen_"),
            Regex("^снимок экрана"),
            // Telegram: photo_2024-05-01_12-30-00.jpg
            Regex("^photo_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}"),
            // WhatsApp: IMG-20240501-WA0001.jpg
            Regex("^img-\\d{8}-wa\\d+")
        )

        val GRAPHIC_EXT = listOf(".png", ".webp", ".bmp", ".gif")
    }
}
