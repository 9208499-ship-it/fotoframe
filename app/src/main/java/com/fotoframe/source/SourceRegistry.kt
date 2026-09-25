package com.fotoframe.source

import android.content.Context
import com.fotoframe.data.prefs.SettingsStore
import com.fotoframe.source.yandex.YandexApi
import com.fotoframe.source.yandex.YandexDiskSource
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Единая точка, где собраны все источники.
 * Добавление нового облака сводится к одной строке в [all].
 */
class SourceRegistry(
    context: Context,
    settings: SettingsStore,
    /** Индекс — музейным источникам, чтобы дописывать подписи картин. */
    dao: () -> com.fotoframe.data.db.PhotoDao
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * Токен для интерцептора. Раньше он читался из DataStore блокирующим
     * вызовом в потоке OkHttp на каждый запрос; теперь поле обновляется
     * из потока настроек, а запрос берёт готовое значение.
     */
    @Volatile
    private var yandexToken: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            settings.settings
                .map { it.yandexToken }
                .distinctUntilChanged()
                .collect { yandexToken = it }
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Пул держится открытым: на слайдшоу запросы идут постоянно,
        // и переустановка TLS-сессии на каждый кадр заметно тормозит показ.
        .connectionPool(okhttp3.ConnectionPool(8, 5, TimeUnit.MINUTES))
        // Превью и прямые ссылки Яндекс.Диска отдаются только с тем же
        // OAuth-заголовком, что и API. Coil про это не знает, поэтому токен
        // подставляется здесь для любых яндексовых хостов, где его ещё нет.
        .addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val yandexHost = host == "yandex.ru" ||
                host.endsWith(".yandex.ru") ||
                host.endsWith(".yandex.net")

            val token = yandexToken
            if (yandexHost && request.header("Authorization") == null && !token.isNullOrBlank()) {
                chain.proceed(
                    request.newBuilder()
                        .header("Authorization", YandexApi.auth(token))
                        .build()
                )
            } else {
                chain.proceed(request)
            }
        }
        .build()

    private val yandexApi: YandexApi = Retrofit.Builder()
        .baseUrl(YandexApi.BASE_URL)
        .client(http)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(YandexApi::class.java)

    val local = LocalGallerySource(context)
    val yandex = YandexDiskSource(yandexApi, settings)
    val smb = SmbSource(context, settings)
    val met = MetMuseumSource(context, http, dao)
    val cleveland = ClevelandMuseumSource(context, http, dao)

    val all: List<MediaSource> = listOf(local, yandex, smb, met, cleveland)

    fun byId(id: String): MediaSource? = all.firstOrNull { it.id == id }

    /** Клиент отдаётся наружу, чтобы Coil грузил картинки через тот же пул. */
    fun httpClient(): OkHttpClient = http
}
