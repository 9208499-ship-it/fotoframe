package com.fotoframe.engine

import android.util.Log
import com.fotoframe.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlin.math.roundToInt

/** Погода сейчас: температура и что за окном. */
data class Weather(
    val temperature: Int,
    val description: String,
    val icon: String,
    /** Кто ответил — показывается в настройках, чтобы было видно, работает ли ключ. */
    val source: String = ""
) {
    /** Одной строкой: «☀ +18°, ясно». */
    val line: String get() = "$icon  ${if (temperature > 0) "+" else ""}$temperature°, $description"
}

/**
 * Погода для подписи поверх кадра.
 *
 * Источник — Open-Meteo: бесплатный, без ключа и регистрации, что для
 * приложения, которое кто угодно скачает с форума, важнее точности —
 * иначе каждому пользователю пришлось бы заводить свой ключ.
 *
 * Запрашивается не чаще раза в полчаса, ответ держится в памяти. При
 * пропаже сети подпись просто исчезает: рамка не место для сообщений об
 * ошибках сети.
 */
class WeatherService(private val sources: SourceRegistry) {

    private val lock = Mutex()

    @Volatile
    private var cached: Weather? = null

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var cachedFor: Pair<Double, Double>? = null

    @Volatile
    private var cachedKey: String = ""

    /** Когда в последний раз спрашивали Яндекс — удачно или нет. */
    @Volatile
    private var lastYandexAt = 0L

    /** Забыть кэш — следующий запрос пойдёт в сеть, в том числе к Яндексу. */
    fun invalidate() {
        cached = null
        lastYandexAt = 0L
    }

    /**
     * Текущая погода. [yandexKey] — личный ключ Яндекс Погоды, если он
     * задан: тогда Яндекс спрашивается первым.
     */
    suspend fun current(
        lat: Double,
        lon: Double,
        yandexKey: String? = null,
        yandexRefreshHours: Int = 2
    ): Weather? = lock.withLock {
        val now = System.currentTimeMillis()
        val place = lat to lon
        val key = yandexKey?.trim().orEmpty()
        val yandexRefresh = yandexRefreshHours.coerceIn(1, 6) * 60L * 60 * 1000

        // Сменили ключ — старый ответ не годится: иначе после ввода ключа
        // ещё полчаса показывался бы прежний поставщик.
        if (key != cachedKey) {
            cached = null
            cachedKey = key
        }

        // Ответ Яндекса живёт дольше: у бесплатного тарифа 30 запросов в
        // сутки на ключ, и спрашивать его раз в полчаса нельзя.
        val fromYandex = cached?.source == YANDEX
        val refresh = if (fromYandex) yandexRefresh else REFRESH_MS
        val stale = maxOf(STALE_MS, refresh + 60L * 60 * 1000)

        val fresh = cached != null &&
            cachedFor == place &&
            now - cachedAt < refresh
        if (fresh) return@withLock cached

        // Яндекс спрашиваем, только если с прошлого обращения к нему
        // прошёл его интервал: иначе, пока держится ответ бесплатного
        // поставщика, каждые полчаса снова тратился бы запрос Яндекса.
        val askYandex = key.isNotBlank() && now - lastYandexAt >= yandexRefresh
        if (askYandex) lastYandexAt = now

        val loaded = fetch(lat, lon, if (askYandex) key else "")
        if (loaded != null) {
            cached = loaded
            cachedAt = now
            cachedFor = place
        } else if (cachedFor != place || now - cachedAt > stale) {
            // Место сменилось или данные слишком старые — старая
            // температура хуже, чем отсутствие подписи. Раньше при
            // недоступном сервере прежнее значение висело бесконечно, и
            // рамка показывала вчерашнюю погоду как текущую.
            cached = null
        }
        cached
    }

    /**
     * Погода от первого ответившего поставщика. Их три, все без ключей и
     * регистрации: Open-Meteo, метеоинститут Норвегии и wttr.in. Одного
     * мало — сервер бывает недоступен из конкретной сети целиком (так и
     * было у автора: Open-Meteo не отвечал ни с одного устройства дома).
     * Первым пробуется тот, кто ответил в прошлый раз.
     */
    private suspend fun fetch(lat: Double, lon: Double, yandexKey: String): Weather? = withContext(Dispatchers.IO) {
        // Личный ключ Яндекса — всегда первым: человек ввёл его, чтобы
        // пользоваться именно Яндексом, и он обычно точнее по России.
        if (yandexKey.isNotBlank()) {
            val y = tryOrNull { yandex(lat, lon, yandexKey) }
            if (y != null) {
                Log.i(TAG, "Погода: Яндекс Погода для %.4f,%.4f — ${y.line}".format(java.util.Locale.US, lat, lon))
                return@withContext y
            }
            Log.w(TAG, "Погода: Яндекс не ответил, пробую бесплатных поставщиков")
        }

        val order = PROVIDERS.indices.sortedBy { if (it == lastGood) -1 else it }
        for (i in order) {
            val w = tryOrNull { PROVIDERS[i].second(lat, lon) }
            if (w != null) {
                Log.i(TAG, "Погода: ${PROVIDERS[i].first} для %.4f,%.4f — ${w.line}".format(java.util.Locale.US, lat, lon))
                lastGood = i
                return@withContext w.copy(source = PROVIDERS[i].first)
            }
        }
        Log.w(TAG, "Погода недоступна: не ответил ни один поставщик")
        null
    }

    // ---------- Яндекс Погода ----------

    /**
     * Яндекс Погода по личному ключу (заголовок X-Yandex-Weather-Key).
     * Сперва REST v2 — его пример дают в кабинете, и он поддерживается
     * тарифом «Погода для умного дома»; если он отказал — GraphQL v3.
     */
    private fun yandex(lat: Double, lon: Double, key: String): Weather? =
        yandexV2(lat, lon, key) ?: yandexV3(lat, lon, key)

    private fun yandexHttp(request: Request.Builder, key: String): String? =
        try {
            http.newCall(request.header("X-Yandex-Weather-Key", key).build()).execute().use { resp ->
                val body = resp.body?.string()
                if (resp.isSuccessful) {
                    body
                } else {
                    // Код и начало ответа в лог: 403 — ключ не подходит к
                    // этому API или ещё не активировался (Яндекс пишет, что
                    // на активацию уходит несколько минут).
                    Log.w(TAG, "Яндекс Погода: ${resp.code} ${body.orEmpty().take(200)}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Яндекс Погода: ${e.message}")
            null
        }

    private fun yandexV2(lat: Double, lon: Double, key: String): Weather? {
        val url = "https://api.weather.yandex.ru/v2/forecast" +
            "?lat=${"%.4f".format(java.util.Locale.US, lat)}&lon=${"%.4f".format(java.util.Locale.US, lon)}" +
            "&lang=ru_RU&limit=1&hours=false"
        val body = yandexHttp(Request.Builder().url(url), key) ?: return null
        val fact = (Json.parseToJsonElement(body) as? JsonObject)?.get("fact") as? JsonObject ?: return null
        val temp = (fact["temp"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        val condition = (fact["condition"] as? JsonPrimitive)?.content.orEmpty()
        val (desc, icon) = yandexCondition(condition)
        return Weather(temp.roundToInt(), desc, icon, YANDEX)
    }

    private fun yandexV3(lat: Double, lon: Double, key: String): Weather? {
        fun ask(fields: String): JsonObject? {
            val query = "{ weatherByPoint(request: { lat: ${"%.4f".format(java.util.Locale.US, lat)}, " +
                "lon: ${"%.4f".format(java.util.Locale.US, lon)} }) { now { $fields } } }"
            val payload = kotlinx.serialization.json.buildJsonObject {
                put("query", JsonPrimitive(query))
            }.toString()
            val body = yandexHttp(
                Request.Builder()
                    .url("https://api.weather.yandex.ru/graphql/query")
                    .post(payload.toRequestBody("application/json".toMediaType())),
                key
            ) ?: return null
            val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
            if (root["errors"] != null) {
                Log.w(TAG, "Яндекс Погода v3: ${root["errors"].toString().take(200)}")
            }
            return ((root["data"] as? JsonObject)?.get("weatherByPoint") as? JsonObject)
                ?.get("now") as? JsonObject
        }

        // Если тариф не отдаёт состояние, просим одну температуру.
        val now = ask("temperature condition") ?: ask("temperature") ?: return null
        val temp = (now["temperature"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        val condition = (now["condition"] as? JsonPrimitive)?.content.orEmpty()
        val (desc, icon) = yandexCondition(condition)
        return Weather(temp.roundToInt(), desc, icon, YANDEX)
    }

    /**
     * Состояние погоды Яндекса по ключевым словам. В v2 коды вида
     * «overcast-and-light-rain», в v3 — заглавными через подчёркивание;
     * приводим к одному виду и смотрим, что в строке есть. Порядок
     * проверок важен: «гроза с дождём» — это гроза, «небольшой снег» —
     * снег, а не облачность.
     */
    private fun yandexCondition(raw: String): Pair<String, String> {
        val c = raw.lowercase().replace('_', '-')
        return when {
            c.isBlank() -> "—" to "•"
            c.contains("thunder") -> "гроза" to "⛈"
            c.contains("hail") -> "град" to "⛈"
            c.contains("wet-snow") || c.contains("sleet") -> "дождь со снегом" to "❄"
            c.contains("light-snow") -> "небольшой снег" to "❄"
            c.contains("snow") -> "снег" to "❄"
            c.contains("showers") -> "ливень" to "🌦"
            c.contains("drizzle") -> "морось" to "🌧"
            c.contains("light-rain") -> "небольшой дождь" to "🌧"
            c.contains("heavy-rain") -> "сильный дождь" to "🌧"
            c.contains("rain") -> "дождь" to "🌧"
            c.contains("fog") || c.contains("mist") -> "туман" to "🌫"
            c.contains("overcast") -> "пасмурно" to "☁"
            c.contains("partly") -> "малооблачно" to "⛅"
            c.contains("cloudy") -> "облачно с прояснениями" to "⛅"
            c.contains("clear") -> "ясно" to "☀"
            else -> "—" to "•"
        }
    }

    @Volatile
    private var lastGood = 0

    /** Короткие тайм-ауты: при трёх поставщиках ждать каждого по 15 с незачем. */
    private val http by lazy {
        sources.httpClient().newBuilder()
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    private fun get(url: String): String? =
        try {
            http.newCall(
                Request.Builder().url(url)
                    // met.no без внятного User-Agent отвечает 403.
                    .header("User-Agent", "FotoFrame/0.8 github.com/9208499-ship-it/fotoframe")
                    .header("Accept-Language", "ru")
                    .build()
            ).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
        } catch (e: Exception) {
            Log.w(TAG, "Погода: ${url.substringAfter("//").substringBefore('/')} — ${e.message}")
            null
        }

    private fun openMeteo(lat: Double, lon: Double): Weather? {
        val body = get(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"
        ) ?: return null
        val current = (Json.parseToJsonElement(body) as? JsonObject)?.get("current") as? JsonObject
            ?: return null
        val temp = (current["temperature_2m"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        val code = (current["weather_code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        return Weather(temp.roundToInt(), describe(code), iconFor(code))
    }

    /**
     * Метеоинститут Норвегии. Отдаёт почасовой прогноз; первая точка —
     * текущий час. Состояние — строкой вида «partlycloudy_day».
     */
    private fun metNo(lat: Double, lon: Double): Weather? {
        val body = get(
            "https://api.met.no/weatherapi/locationforecast/2.0/compact" +
                "?lat=${"%.4f".format(java.util.Locale.US, lat)}&lon=${"%.4f".format(java.util.Locale.US, lon)}"
        ) ?: return null
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val series = (root["properties"] as? JsonObject)?.get("timeseries")
            as? kotlinx.serialization.json.JsonArray ?: return null
        val first = series.firstOrNull() as? JsonObject ?: return null
        val data = first["data"] as? JsonObject ?: return null
        val details = ((data["instant"] as? JsonObject)?.get("details") as? JsonObject) ?: return null
        val temp = (details["air_temperature"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
        val symbol = (((data["next_1_hours"] as? JsonObject)?.get("summary") as? JsonObject)
            ?.get("symbol_code") as? JsonPrimitive)?.content.orEmpty()
            .substringBefore('_')
        val (desc, icon) = when {
            symbol.contains("thunder") -> "гроза" to "⛈"
            symbol.contains("sleet") -> "мокрый снег" to "❄"
            symbol.contains("snow") -> "снег" to "❄"
            symbol.contains("showers") -> "ливень" to "🌦"
            symbol.contains("rain") -> "дождь" to "🌧"
            symbol == "fog" -> "туман" to "🌫"
            symbol == "cloudy" -> "пасмурно" to "☁"
            symbol == "partlycloudy" -> "переменная облачность" to "⛅"
            symbol == "fair" -> "малооблачно" to "⛅"
            symbol == "clearsky" -> "ясно" to "☀"
            else -> "—" to "•"
        }
        return Weather(temp.roundToInt(), desc, icon)
    }

    /**
     * wttr.in: короткий текстовый ответ «+15°C|Переменная облачность»,
     * описание уже по-русски. Значок подбирается по словам описания.
     */
    private fun wttr(lat: Double, lon: Double): Weather? {
        val body = get(
            "https://wttr.in/${"%.4f".format(java.util.Locale.US, lat)},${"%.4f".format(java.util.Locale.US, lon)}" +
                "?format=%25t%7C%25C&lang=ru"
        )?.trim() ?: return null
        val parts = body.split('|')
        if (parts.size < 2) return null
        val temp = Regex("-?\\d+").find(parts[0].replace("+", ""))?.value?.toIntOrNull() ?: return null
        // wttr.in иногда отвечает по-английски, несмотря на lang=ru, —
        // тогда переводим сами по ключевым словам.
        val desc = englishToRussian(parts[1].trim().lowercase())
        val icon = when {
            desc.contains("гроз") -> "⛈"
            desc.contains("снег") -> "❄"
            desc.contains("ливн") -> "🌦"
            desc.contains("дожд") || desc.contains("морос") -> "🌧"
            desc.contains("туман") || desc.contains("дымк") -> "🌫"
            desc.contains("пасмурн") -> "☁"
            desc.contains("облач") -> "⛅"
            desc.contains("ясно") || desc.contains("солнеч") -> "☀"
            else -> "•"
        }
        return Weather(temp, desc, icon)
    }

    private fun englishToRussian(d: String): String {
        if (d.any { it in 'а'..'я' }) return d
        return when {
            d.contains("thunder") -> "гроза"
            d.contains("sleet") -> "дождь со снегом"
            d.contains("snow") -> "снег"
            d.contains("shower") -> "ливень"
            d.contains("drizzle") -> "морось"
            d.contains("light rain") -> "небольшой дождь"
            d.contains("rain") -> "дождь"
            d.contains("fog") || d.contains("mist") -> "туман"
            d.contains("overcast") -> "пасмурно"
            d.contains("partly") -> "переменная облачность"
            d.contains("cloud") -> "облачно"
            d.contains("clear") || d.contains("sunny") -> "ясно"
            else -> d
        }
    }

    private val PROVIDERS: List<Pair<String, (Double, Double) -> Weather?>> = listOf(
        "Open-Meteo" to ::openMeteo,
        "met.no" to ::metNo,
        "wttr.in" to ::wttr
    )

    /**
     * Коды WMO. Группируем крупно: на экране, который видят мельком с
     * трёх метров, «слабый ливневый дождь» и «дождь» — одно и то же.
     */
    private fun describe(code: Int): String = when (code) {
        0 -> "ясно"
        1 -> "малооблачно"
        2 -> "переменная облачность"
        3 -> "пасмурно"
        in 45..48 -> "туман"
        in 51..57 -> "морось"
        in 61..67 -> "дождь"
        in 71..77 -> "снег"
        in 80..82 -> "ливень"
        in 85..86 -> "снегопад"
        in 95..99 -> "гроза"
        else -> "—"
    }

    private fun iconFor(code: Int): String = when (code) {
        0 -> "☀"
        1, 2 -> "⛅"
        3 -> "☁"
        in 45..48 -> "🌫"
        in 51..67 -> "🌧"
        in 71..77, in 85..86 -> "❄"
        in 80..82 -> "🌦"
        in 95..99 -> "⛈"
        else -> "•"
    }

    private companion object {
        const val TAG = "Weather"

        /** Подпись источника для ответов Яндекса. */
        const val YANDEX = "Яндекс Погода"

        /** Чаще получаса обновлять незачем, а серверу — лишняя нагрузка. */
        const val REFRESH_MS = 30L * 60 * 1000

        /** Старше этого погода не показывается, даже если новой взять негде. */
        const val STALE_MS = 3L * 60 * 60 * 1000
    }
}
