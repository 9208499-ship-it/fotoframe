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
import kotlin.math.roundToInt

/** Погода сейчас: температура и что за окном. */
data class Weather(
    val temperature: Int,
    val description: String,
    val icon: String
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

    suspend fun current(lat: Double, lon: Double): Weather? = lock.withLock {
        val now = System.currentTimeMillis()
        val place = lat to lon

        val fresh = cached != null &&
            cachedFor == place &&
            now - cachedAt < REFRESH_MS
        if (fresh) return@withLock cached

        val loaded = fetch(lat, lon)
        if (loaded != null) {
            cached = loaded
            cachedAt = now
            cachedFor = place
        } else if (cachedFor != place) {
            // Место сменилось, а новых данных нет — старая температура
            // от другого города хуже, чем отсутствие подписи.
            cached = null
        }
        cached
    }

    private suspend fun fetch(lat: Double, lon: Double): Weather? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code"

        val body = try {
            sources.httpClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (e: Exception) {
            e.rethrowIfCancelled()
            Log.w(TAG, "Погода недоступна: ${e.message}")
            null
        } ?: return@withContext null

        val current = runCatching {
            (Json.parseToJsonElement(body) as? JsonObject)?.get("current") as? JsonObject
        }.getOrNull() ?: return@withContext null

        val temp = (current["temperature_2m"] as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?: return@withContext null
        val code = (current["weather_code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

        Weather(
            temperature = temp.roundToInt(),
            description = describe(code),
            icon = iconFor(code)
        )
    }

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

        /** Чаще получаса обновлять незачем, а серверу — лишняя нагрузка. */
        const val REFRESH_MS = 30L * 60 * 1000
    }
}
