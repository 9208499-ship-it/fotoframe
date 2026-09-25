package com.fotoframe.engine

import android.util.Log
import com.fotoframe.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    val source: String = "",
    /** Скорость ветра, м/с; null — поставщик не сообщил. */
    val windSpeed: Int? = null,
    /**
     * Почасовые осадки на ближайшие часы — из них подсказка вида «дождь
     * с 15:00» считается в момент показа, чтобы она не устаревала, пока
     * ответ поставщика лежит в кэше.
     */
    val hours: List<HourPrecip> = emptyList()
) {
    /** «дождь с 15:00», «снег до 17:00» или null, если в ближайшие часы без перемен. */
    fun precipHint(now: Long = System.currentTimeMillis()): String? = computePrecipHint(hours, now)

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
        yandexRefreshMinutes: Int = 120
    ): Weather? = lock.withLock {
        val now = System.currentTimeMillis()
        val place = lat to lon
        val key = yandexKey?.trim().orEmpty()
        val yandexRefresh = yandexRefreshMinutes.coerceIn(30, 360) * 60L * 1000

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
            "&lang=ru_RU&limit=2&hours=true"
        val body = yandexHttp(Request.Builder().url(url), key) ?: return null
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val fact = root["fact"] as? JsonObject ?: return null
        val temp = fact.num("temp") ?: return null
        val condition = fact.str("condition").orEmpty()
        val (desc, icon) = yandexCondition(condition)

        // Почасовой прогноз: forecasts[день].hours[час], время — hour_ts в
        // секундах. prec_type: 1 дождь, 2 дождь со снегом, 3 снег, 4 град.
        val hours = ArrayList<HourPrecip>()
        (root["forecasts"] as? JsonArray)?.forEach { day ->
            ((day as? JsonObject)?.get("hours") as? JsonArray)?.forEach { h ->
                val o = h as? JsonObject ?: return@forEach
                val at = o.num("hour_ts")?.toLong()?.times(1000) ?: return@forEach
                val mm = o.num("prec_mm") ?: 0.0
                val type = o.num("prec_type")?.toInt() ?: 0
                val kind = when {
                    mm <= 0.0 && type == 0 -> null
                    o.str("condition").orEmpty().contains("thunder") -> "гроза"
                    type == 2 -> "мокрый снег"
                    type == 3 -> "снег"
                    type == 4 -> "град"
                    else -> "дождь"
                }
                hours += HourPrecip(at, kind)
            }
        }
        return Weather(
            temp.roundToInt(), desc, icon, YANDEX,
            windSpeed = fact.num("wind_speed")?.roundToInt(),
            hours = hours
        )
    }

    private fun yandexV3(lat: Double, lon: Double, key: String): Weather? {
        fun ask(fields: String, forecast: String = ""): JsonObject? {
            val query = "{ weatherByPoint(request: { lat: ${"%.4f".format(java.util.Locale.US, lat)}, " +
                "lon: ${"%.4f".format(java.util.Locale.US, lon)} }) { now { $fields } $forecast } }"
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
            return (root["data"] as? JsonObject)?.get("weatherByPoint") as? JsonObject
        }

        // С подробностями, без них и совсем коротко — что тариф отдаст.
        val point = ask(
            "temperature condition windSpeed",
            "forecast { hours(first: 13) { edges { node { timestamp prec precType } } } }"
        ) ?: ask("temperature condition") ?: ask("temperature") ?: return null
        val now = point["now"] as? JsonObject ?: return null
        val temp = now.num("temperature") ?: return null
        val condition = now.str("condition").orEmpty()
        val (desc, icon) = yandexCondition(condition)

        val hours = ArrayList<HourPrecip>()
        ((((point["forecast"] as? JsonObject)?.get("hours") as? JsonObject)?.get("edges")) as? JsonArray)
            ?.forEach { e ->
                val node = (e as? JsonObject)?.get("node") as? JsonObject ?: return@forEach
                val at = parseTime(node.str("timestamp")) ?: return@forEach
                val mm = node.num("prec") ?: 0.0
                val type = node.str("precType").orEmpty().lowercase()
                val kind = when {
                    mm <= 0.0 -> null
                    type.contains("sleet") -> "мокрый снег"
                    type.contains("snow") -> "снег"
                    type.contains("hail") -> "град"
                    else -> "дождь"
                }
                hours += HourPrecip(at, kind)
            }
        return Weather(
            temp.roundToInt(), desc, icon, YANDEX,
            windSpeed = now.num("windSpeed")?.roundToInt(),
            hours = hours
        )
    }

    /** Время в секундах, миллисекундах или ISO-строкой — что пришлёт поставщик. */
    private fun parseTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        raw.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.content?.toDoubleOrNull()

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
                "?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,wind_speed_10m" +
                "&wind_speed_unit=ms&hourly=precipitation,weather_code&forecast_hours=13&timeformat=unixtime"
        ) ?: return null
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
        val current = root["current"] as? JsonObject ?: return null
        val temp = current.num("temperature_2m") ?: return null
        val code = current.num("weather_code")?.toInt() ?: 0

        val hours = ArrayList<HourPrecip>()
        (root["hourly"] as? JsonObject)?.let { h ->
            val times = h["time"] as? JsonArray
            val prec = h["precipitation"] as? JsonArray
            val codes = h["weather_code"] as? JsonArray
            if (times != null) for (i in times.indices) {
                val at = (times[i] as? JsonPrimitive)?.content?.toLongOrNull()?.times(1000) ?: continue
                val mm = (prec?.getOrNull(i) as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0
                val c = (codes?.getOrNull(i) as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val kind = when {
                    mm < 0.1 -> null
                    c in 95..99 -> "гроза"
                    c in 71..77 || c in 85..86 -> "снег"
                    else -> "дождь"
                }
                hours += HourPrecip(at, kind)
            }
        }
        return Weather(
            temp.roundToInt(), describe(code), iconFor(code),
            windSpeed = current.num("wind_speed_10m")?.roundToInt(),
            hours = hours
        )
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

        // Почасовой ряд: время ISO в UTC, осадки за следующий час.
        val hours = ArrayList<HourPrecip>()
        for (item in series.take(13)) {
            val o = item as? JsonObject ?: continue
            val at = parseTime(o.str("time")) ?: continue
            val next = (o["data"] as? JsonObject)?.get("next_1_hours") as? JsonObject ?: continue
            val mm = ((next["details"] as? JsonObject)?.num("precipitation_amount")) ?: 0.0
            val sym = ((next["summary"] as? JsonObject)?.str("symbol_code")).orEmpty()
            val kind = when {
                mm < 0.1 -> null
                sym.contains("thunder") -> "гроза"
                sym.contains("sleet") -> "мокрый снег"
                sym.contains("snow") -> "снег"
                else -> "дождь"
            }
            hours += HourPrecip(at, kind)
        }
        return Weather(
            temp.roundToInt(), desc, icon,
            windSpeed = details.num("wind_speed")?.roundToInt(),
            hours = hours
        )
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

/** Час прогноза: время начала (мс) и вид осадков; null — без осадков. */
data class HourPrecip(val at: Long, val kind: String?)

/**
 * Подсказка об осадках на [WINDOW_H] часов вперёд. Сейчас сухо — когда
 * начнётся: «дождь с 15:00». Уже идёт — когда кончится: «дождь до 17:00».
 * Если в окне ничего не меняется — null: строка появляется, только когда
 * есть что сказать. Начало на завтра — «снег завтра с 06:00».
 */
internal fun computePrecipHint(hours: List<HourPrecip>, now: Long): String? {
    val hour = 60L * 60 * 1000
    val ahead = hours
        .filter { it.at + hour > now && it.at < now + WINDOW_H * hour }
        .sortedBy { it.at }
    if (ahead.isEmpty()) return null

    val first = ahead.first()
    return if (first.kind != null) {
        val stop = ahead.firstOrNull { it.kind == null } ?: return null
        "${first.kind} ${whenText(stop.at, now, "до")}"
    } else {
        val start = ahead.firstOrNull { it.kind != null } ?: return null
        "${start.kind} ${whenText(start.at, now, "с")}"
    }
}

private fun whenText(at: Long, now: Long, prep: String): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = now
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR)
    cal.timeInMillis = at
    val day = cal.get(java.util.Calendar.DAY_OF_YEAR)
    val time = "%02d:00".format(cal.get(java.util.Calendar.HOUR_OF_DAY))
    return if (day != today) "завтра $prep $time" else "$prep $time"
}

/** Окно подсказки об осадках, часов. */
private const val WINDOW_H = 12
