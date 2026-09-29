package com.fotoframe.source

import android.util.Log
import com.fotoframe.engine.rethrowIfCancelled
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Русская подпись картины.
 *
 * Сначала Викиданные: там названия картин и имена художников заведены
 * людьми, и «Винсент Ван Гог — Пшеничное поле с кипарисами» лучше любого
 * машинного подстрочника. Метрополитен даёт ссылки на Викиданные и для
 * картины, и для автора. Чего в Викиданных по-русски нет — машинный
 * перевод MyMemory (бесплатный, без ключа, лимит около пяти тысяч слов
 * в сутки). Кончился лимит — до завтра подпись остаётся на языке музея.
 *
 * Перевод делается один раз, при первом показе картины, и хранится в
 * индексе.
 */
class ArtTranslator(private val http: OkHttpClient) {

    /** Когда MyMemory сказал «лимит на сегодня исчерпан» — до завтра не спрашиваем. */
    @Volatile
    private var quotaUntil = 0L

    /**
     * Подпись по-русски для Метрополитена: из частей, с Викиданными.
     * [objectQ], [artistQ] — идентификаторы вида «Q5582», если музей их дал.
     */
    fun metCaption(artist: String?, title: String?, date: String?, objectQ: String?, artistQ: String?): String? {
        val labels = wikidataRu(listOfNotNull(objectQ, artistQ))
        val ruTitle = objectQ?.let { labels[it] } ?: title?.let { machine(it) }
        val ruArtist = artistQ?.let { labels[it] } ?: artist
        if (ruTitle == null && ruArtist == artist) return null
        return compose(ruArtist, ruTitle ?: title, date?.let { ruDate(it) })
    }

    /**
     * Подпись по-русски для готовой строки «Автор — Название, год» —
     * у Кливленда ссылок на Викиданные нет, переводится целиком.
     */
    fun wholeCaption(caption: String): String? = machine(caption)

    // ---------- Викиданные ----------

    private fun wikidataRu(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val url = "https://www.wikidata.org/w/api.php?action=wbgetentities&props=labels&languages=ru" +
            "&format=json&ids=" + ids.joinToString("|")
        val body = get(url) ?: return emptyMap()
        val entities = (Json.parseToJsonElement(body) as? JsonObject)?.get("entities") as? JsonObject
            ?: return emptyMap()
        return entities.mapNotNull { (id, e) ->
            val label = (((e as? JsonObject)?.get("labels") as? JsonObject)?.get("ru") as? JsonObject)
                ?.get("value") as? JsonPrimitive
            label?.content?.takeIf { it.isNotBlank() }?.let { id to it }
        }.toMap()
    }

    // ---------- машинный перевод ----------

    private fun machine(text: String): String? {
        if (text.isBlank()) return null
        if (System.currentTimeMillis() < quotaUntil) return null
        // Уже по-русски — переводить нечего.
        if (text.any { it in 'а'..'я' || it in 'А'..'Я' }) return text

        val url = "https://api.mymemory.translated.net/get?langpair=en%7Cru&q=" +
            URLEncoder.encode(text.take(450), "UTF-8")
        val body = get(url) ?: return null
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null

        if ((root["quotaFinished"] as? JsonPrimitive)?.content == "true") {
            quotaUntil = System.currentTimeMillis() + 12L * 60 * 60 * 1000
            Log.w(TAG, "Перевод: дневной лимит исчерпан, до завтра подписи на языке музея")
            return null
        }
        if ((root["responseStatus"] as? JsonPrimitive)?.content != "200") return null

        val out = (((root["responseData"] as? JsonObject)?.get("translatedText")) as? JsonPrimitive)
            ?.content?.trim() ?: return null
        // Служебные предупреждения сервиса приходят вместо перевода.
        if (out.isBlank() || out.startsWith("MYMEMORY", ignoreCase = true)) return null
        return unescape(out)
    }

    // ---------- мелочи ----------

    /** «ca. 1665» → «ок. 1665», «1880s» → «1880-е». */
    private fun ruDate(d: String): String =
        d.replace(Regex("\\b(ca\\.|c\\.|circa)\\s*", RegexOption.IGNORE_CASE), "ок. ")
            .replace(Regex("(\\d{3,4})s\\b"), "$1-е")

    private fun compose(artist: String?, title: String?, date: String?): String =
        listOfNotNull(
            listOfNotNull(artist, title).joinToString(" — ").takeIf { it.isNotBlank() },
            date
        ).joinToString(", ")

    private fun unescape(s: String) = s
        .replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun get(url: String): String? = try {
        http.newCall(
            Request.Builder().url(url)
                .header("User-Agent", "FotoFrame (Android TV photo frame; github.com/9208499-ship-it/fotoframe)")
                .build()
        ).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (e: Exception) {
        e.rethrowIfCancelled()
        Log.w(TAG, "Перевод: ${url.substringAfter("//").substringBefore('/')} — ${e.message}")
        null
    }

    companion object {
        private const val TAG = "Museum"

        /** «https://www.wikidata.org/wiki/Q5582» → «Q5582». */
        fun qid(url: String?): String? = url?.substringAfterLast('/')?.takeIf { it.matches(Regex("Q\\d+")) }
    }
}
