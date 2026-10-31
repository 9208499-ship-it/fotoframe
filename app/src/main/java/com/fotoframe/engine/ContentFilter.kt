package com.fotoframe.engine

import com.fotoframe.data.db.FilterRow
import com.fotoframe.data.db.PhotoDao
import com.fotoframe.data.prefs.SlideshowSettings
import com.fotoframe.data.prefs.enabledMuseums

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

    /**
     * Итог пересчёта. [hidden] — все не допущенные, но большая их часть
     * обычно не «отсеяна»: это ролики в режиме фото, картины музеев, снимки
     * отключённого источника. Человек видел «отсеяно 12 000» при выключенном
     * фильтре и решал, что рамка теряет его снимки. Теперь причины врозь.
     */
    data class Outcome(
        val enabled: Int,
        val hidden: Int,
        /** Другой режим показа: ролики в режиме фото и наоборот, картины вне режима картин. */
        val otherMode: Int = 0,
        /** Источник не подключён или выключен. */
        val inactive: Int = 0,
        /** Отсеяно фильтром содержимого — по причинам. */
        val byReason: Map<String, Int> = emptyMap()
    ) {
        fun describe(): String = buildString {
            append("К показу допущено $enabled.")
            if (otherMode > 0) append(" Другой режим показа (ролики, картины): $otherMode.")
            if (inactive > 0) append(" Источник отключён: $inactive.")
            if (byReason.isNotEmpty()) {
                append(" Отсеяно фильтром: ")
                append(byReason.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} — ${it.value}" })
                append('.')
            } else {
                append(" Фильтром не отсеяно ничего.")
            }
        }
    }

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
        var okTotal = 0
        var badTotal = 0
        var otherMode = 0
        var inactive = 0
        val byReason = HashMap<String, Int>()
        for (r in rows) {
            // Картины фильтр содержимого не касается: он про скриншоты,
            // мелкие файлы и служебные папки — у музея таких нет, а размеров
            // до первого показа неизвестно.
            val museum = r.sourceId in com.fotoframe.source.MUSEUM_SOURCES
            // Ролики — только в режиме «Видео», снимки — только вне его.
            // Фильтр содержимого (разрешение, размер, скриншоты) — про
            // снимки, к роликам не применяется.
            val video = s.showMode == "video"
            // Длительность 0 — ещё не известна: такой ролик допускается,
            // её узнают перед показом.
            val tooShort = r.durationMs in 1 until s.minVideoSeconds * 1000L
            val artMode = s.showMode == "art"
            val reason: String? = when {
                // Картины в режиме фото и снимки в режиме картин — не отсев, а другой режим.
                museum != artMode -> MODE
                r.sourceId !in active -> INACTIVE
                museum -> null
                video -> when {
                    !r.isVideo -> MODE
                    tooShort -> "короткие ролики"
                    else -> null
                }
                r.isVideo -> MODE
                else -> rejectReason(r, s, minPixels, minBytes)
            }
            val ok = reason == null
            when (reason) {
                null -> {}
                MODE -> otherMode++
                INACTIVE -> inactive++
                else -> byReason[reason] = (byReason[reason] ?: 0) + 1
            }
            // Пишем в базу только то, что меняется. Раньше при каждом
            // пересчёте переписывались все записи — на 190 тысячах снимков
            // это секунды работы с диском при каждой смене режима.
            if (ok) okTotal++ else badTotal++
            if (ok != r.enabled) {
                if (ok) enable += r.id else disable += r.id
            }
        }

        // По 500 за раз: у SQLite ограничение на число параметров в запросе.
        enable.chunked(CHUNK).forEach { dao.setEnabledByIds(it, true) }
        disable.chunked(CHUNK).forEach { dao.setEnabledByIds(it, false) }

        return Outcome(
            enabled = okTotal,
            hidden = badTotal,
            otherMode = otherMode,
            inactive = inactive,
            byReason = byReason
        )
    }

    /** Какие источники настроены. Устройство считается настроенным всегда. */
    private fun activeSources(s: SlideshowSettings): Set<String> = buildSet {
        // Фотографии и картины — разные режимы, не смешиваются.
        if (s.showMode == "art") {
            addAll(s.enabledMuseums())
        } else {
            add("local")
            if (!s.yandexToken.isNullOrBlank()) add("yandex")
            if (s.smbHost.isNotBlank() && s.smbShare.isNotBlank()) add("smb")
        }
    }

    /** Почему снимок не допущен фильтром содержимого; null — допущен. */
    private fun rejectReason(r: FilterRow, s: SlideshowSettings, minPixels: Long, minBytes: Long): String? {
        val name = r.displayName.trim().lowercase()
        val album = r.albumName?.trim()?.lowercase()

        if (s.skipJunkFolders) {
            if (album != null && album in JUNK_ALBUMS) return "служебные папки"
            if (JUNK_NAMES.any { it.containsMatchIn(name) }) return "скриншоты и мессенджеры"
        }

        if (s.skipPng && GRAPHIC_EXT.any { name.endsWith(it) }) return "PNG и графика"

        val w = r.width
        val h = r.height
        if (minPixels > 0 && w > 0 && h > 0 && w.toLong() * h < minPixels) return "малое разрешение"
        if (minBytes > 0 && r.sizeBytes > 0 && r.sizeBytes < minBytes) return "малый размер файла"

        if (s.maxAspectRatio > 1f && w > 0 && h > 0) {
            val aspect = maxOf(w, h).toFloat() / minOf(w, h)
            if (aspect > s.maxAspectRatio) return "панорамы и узкие кадры"
        }

        return null
    }

    private companion object {
        const val CHUNK = 500
        const val MODE = "\u0000mode"
        const val INACTIVE = "\u0000inactive"

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
