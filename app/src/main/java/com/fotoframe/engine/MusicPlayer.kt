package com.fotoframe.engine

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.fotoframe.data.prefs.SlideshowSettings
import com.fotoframe.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Фоновая музыка под показ.
 *
 * Три источника: музыка с устройства (вся или из выбранной папки),
 * папка на сетевом хранилище и интернет-радио по адресу потока. Списки
 * перемешиваются и играют по кругу; файлы с хранилища качаются в кэш
 * перед воспроизведением, следующий — заранее.
 *
 * Проигрыватель — ExoPlayer из Android Media3, а не встроенный
 * MediaPlayer: тот плохо держит интернет-радио — теряет поток на мелких
 * сетевых провалах и отпускает Wi-Fi, когда устройство экономит энергию.
 * ExoPlayer держит блокировку сети на время воспроизведения
 * ([C.WAKE_MODE_NETWORK]) и понимает название песни, которое станции
 * передают в потоке.
 *
 * Все обращения к игроку — с главного потока: так требует ExoPlayer, и
 * так вызывают [start]/[stop] модель показа и обработчики игрока.
 */
class MusicPlayer(
    private val context: Context,
    private val sources: SourceRegistry,
    private val scope: CoroutineScope,
    /** Сообщает, что сейчас играет (null — ничего), для подписи в настройках. */
    private val onNowPlaying: (String?) -> Unit = {}
) {
    private var player: ExoPlayer? = null
    private var buildJob: Job? = null
    private var retryJob: Job? = null
    private var playlist: List<Track> = emptyList()
    private var index = -1

    /**
     * Что сейчас должно играть: режим, папки, адрес. По нему решается, надо
     * ли перезапускать: смена интервала показа или погоды музыку не трогает.
     * null — музыка остановлена.
     */
    private var currentKey: List<String>? = null
    private var stationTitle: String? = null
    private var volume = 0.4f

    var nowPlaying: String? = null
        private set(value) {
            field = value
            onNowPlaying(value)
        }

    private data class Track(val title: String, val uri: Uri? = null, val smbPath: String? = null)

    /** Запустить или оставить играть, если играет то же самое. */
    fun start(s: SlideshowSettings) {
        volume = s.musicVolume.coerceIn(0, 100) / 100f
        player?.volume = volume

        if (s.musicMode == "off") { stop(); return }
        val key = listOf(s.musicMode, s.musicSmbFolder, s.musicDeviceFolder, s.musicStreamUrl)
        if (key == currentKey) return

        stop()
        currentKey = key
        buildJob = scope.launch {
            when (s.musicMode) {
                "stream" -> {
                    stationTitle = MusicPlayer.PRESETS.firstOrNull { it.third == s.musicStreamUrl }?.first
                        ?: s.musicStreamUrl.substringAfter("//").substringBefore('/')
                    playUri(Uri.parse(s.musicStreamUrl), stationTitle!!)
                }
                else -> {
                    playlist = tryOrNull { buildPlaylist(s) }.orEmpty().shuffled()
                    if (playlist.isEmpty()) {
                        Log.w(TAG, "Музыка: список пуст (${s.musicMode})")
                        nowPlaying = null
                        return@launch
                    }
                    Log.i(TAG, "Музыка: ${playlist.size} треков (${s.musicMode})")
                    index = -1
                    playNext()
                }
            }
        }
    }

    fun stop() {
        currentKey = null
        buildJob?.cancel()
        retryJob?.cancel()
        releasePlayer()
        nowPlaying = null
    }

    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100) / 100f
        player?.volume = volume
    }

    // ---------- списки ----------

    private suspend fun buildPlaylist(s: SlideshowSettings): List<Track> = when (s.musicMode) {
        "device" -> deviceTracks(s.musicDeviceFolder)
        "smb" -> smbTracks(s.musicSmbFolder)
        else -> emptyList()
    }

    private suspend fun deviceTracks(folder: String): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        // Только настоящая музыка: без рингтонов, уведомлений и звуков
        // приложений, которые тоже лежат в аудиобиблиотеке. Папка — по
        // относительному пути (Android 10+); на старых версиях путь в
        // библиотеке другой, там играет всё.
        var selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        var args: Array<String>? = null
        if (folder.isNotBlank() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            selection += " AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("$folder%")
        }
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                while (c.moveToNext() && out.size < MAX_TRACKS) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: "Без названия"
                    val artist = c.getString(artistCol)?.takeIf { it.isNotBlank() && it != "<unknown>" }
                    out += Track(
                        title = if (artist != null) "$artist — $title" else title,
                        uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Музыка с устройства: ${it.message}") }
        out
    }

    /**
     * Папки устройства, в которых система видит музыку, — для выбора в
     * настройках. Относительные пути вида «Music/Jazz/». На Android до 10
     * относительного пути нет — список пуст, играет всё.
     */
    suspend fun deviceFolders(): List<String> = withContext(Dispatchers.IO) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return@withContext emptyList()
        val out = LinkedHashSet<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.RELATIVE_PATH),
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.RELATIVE_PATH} ASC"
            )?.use { c ->
                val col = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                while (c.moveToNext() && out.size < 300) {
                    c.getString(col)?.takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
        }.onFailure { Log.w(TAG, "Папки музыки: ${it.message}") }
        out.toList()
    }

    private suspend fun smbTracks(folder: String): List<Track> =
        sources.smb.listAudio(folder).take(MAX_TRACKS).map { path ->
            Track(title = path.substringAfterLast('\\').substringBeforeLast('.'), smbPath = path)
        }

    // ---------- воспроизведение ----------

    /** Музыка должна играть — а не была остановлена, пока шло ожидание. */
    private val running: Boolean get() = currentKey != null

    private suspend fun playNext() {
        if (!running || playlist.isEmpty()) return
        index = (index + 1) % playlist.size
        val track = playlist[index]

        val uri: Uri? = when {
            track.uri != null -> track.uri
            track.smbPath != null -> tryOrNull { sources.smb.fetchToCache(track.smbPath) }?.let { Uri.fromFile(it) }
            else -> null
        }
        if (uri == null) {
            // Не скачался — дальше, но не в тугом цикле.
            delay(2_000)
            playNext()
            return
        }

        // Следующий трек с хранилища качается заранее, пока играет этот.
        playlist.getOrNull((index + 1) % playlist.size)?.smbPath?.let { next ->
            scope.launch { tryOrNull { sources.smb.fetchToCache(next) } }
        }

        stationTitle = null
        playUri(uri, track.title)
    }

    private fun playUri(uri: Uri, title: String) {
        if (!running) return
        val p = player ?: createPlayer().also { player = it }
        p.setMediaItem(MediaItem.fromUri(uri))
        p.prepare()
        p.playWhenReady = true
        nowPlaying = title
        Log.i(TAG, "Музыка: играет «$title»")
    }

    private fun createPlayer(): ExoPlayer {
        val p = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // Блокировка процессора и Wi-Fi на время воспроизведения —
            // иначе приставка, экономя энергию, роняла поток через пару минут.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.volume = volume

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // Трек закончился — следующий. У радио конца нет; если поток
                // всё же «закончился», это обрыв — переподключаемся.
                if (state == Player.STATE_ENDED && running) {
                    if (stationTitle != null) reconnect("поток закончился") else scope.launch { playNext() }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Музыка: ${error.errorCodeName} — ${error.message}")
                if (!running) return
                if (stationTitle != null) {
                    reconnect(error.errorCodeName)
                } else {
                    // Битый файл в плейлисте — пропускаем.
                    scope.launch { delay(1_000); playNext() }
                }
            }

            // Название песни, которое радиостанция передаёт в потоке.
            override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                val station = stationTitle ?: return
                val song = metadata.title?.toString()?.takeIf { it.isNotBlank() }
                nowPlaying = if (song != null && song != station) "$station — $song" else station
            }
        })
        return p
    }

    /**
     * Переподключение к радио: пауза растёт от 3 до 30 секунд, чтобы не
     * долбить сервер, если он лёг надолго. Первая удачная секунда
     * воспроизведения сбрасывает счётчик.
     */
    private var attempts = 0

    private fun reconnect(reason: String) {
        if (retryJob?.isActive == true) return
        val key = currentKey ?: return
        retryJob = scope.launch {
            attempts++
            val wait = (3_000L * attempts).coerceAtMost(30_000L)
            Log.i(TAG, "Музыка: переподключение через ${wait / 1000} с ($reason)")
            delay(wait)
            if (currentKey != key) return@launch
            val url = key[3]
            player?.let { p ->
                p.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                p.prepare()
                p.playWhenReady = true
            }
            // Если за 15 секунд не упало — считаем, что восстановились.
            delay(15_000)
            if (player?.isPlaying == true) attempts = 0
        }
    }

    private fun releasePlayer() {
        player?.let { p -> runCatching { p.stop(); p.release() } }
        player = null
        stationTitle = null
        attempts = 0
    }

    companion object {
        private const val TAG = "Music"
        private const val MAX_TRACKS = 2000

        /**
         * Готовые станции со спокойной музыкой, без регистрации. Отобраны
         * проверкой из сети в России (сентябрь 2026): SomaFM, Jazz24 и
         * часть российских станций оттуда недоступны и в список не вошли.
         * Адреса у станций иногда меняются — тогда свой можно ввести руками.
         */
        val PRESETS: List<Triple<String, String, String>> = listOf(
            Triple("Радио JAZZ", "джаз, Москва, 89.1 FM", "https://nashe1.hostingradio.ru/jazz-128.mp3"),
            Triple("Radio Paradise — Mellow Mix", "спокойный рок и фолк, без рекламы", "https://stream.radioparadise.com/mellow-128"),
            Triple("Radio Paradise — Serenity", "тихий эмбиент, без рекламы", "https://stream.radioparadise.com/serenity"),
            Triple("Radio Paradise — Global Mix", "музыка мира, без рекламы", "https://stream.radioparadise.com/global-128"),
            Triple("Radio Paradise — Main Mix", "эклектика, без рекламы", "https://stream.radioparadise.com/aac-128"),
            Triple("Спокойное радио", "лёгкая фоновая музыка", "http://listen1.myradio24.com:9000/6262")
        )
    }
}
