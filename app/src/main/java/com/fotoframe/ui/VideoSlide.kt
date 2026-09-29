package com.fotoframe.ui

import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.fotoframe.App
import com.fotoframe.source.RoutingDataSource

/**
 * Видеоролик во весь экран — целиком и со звуком, как в обычном плеере.
 *
 * Свой ExoPlayer на каждый ролик: создаётся, когда кадр появляется, и
 * освобождается, когда уходит. Ролик с сетевой папки читается потоком,
 * по кускам ([RoutingDataSource]), с Диска — по прямой ссылке, с
 * устройства — как обычный файл.
 *
 * [paused] — пауза с пульта (OK). [muted] — без звука: так в заставке,
 * которая включается сама, в том числе ночью. Кончился ролик —
 * [onEnded], ошибка — [onError]: смену кадра решает модель показа.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun VideoSlide(
    uri: String,
    paused: Boolean,
    muted: Boolean,
    onEnded: () -> Unit,
    /** Ошибка; true — формат не поддерживается, ролик не сыграет никогда. */
    onError: (unsupported: Boolean) -> Unit,
    title: String = "",
    /** Длительность стала известна, мс. */
    onDuration: (Long) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ended by rememberUpdatedState(onEnded)
    val failed by rememberUpdatedState(onError)
    val durationKnown by rememberUpdatedState(onDuration)

    val player = remember(uri) {
        val smb = (context.applicationContext as App).sources.smb
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(RoutingDataSource.Factory(context, smb)))
            // Буфер для сети: после вынужденной остановки копим 8 секунд, а
            // не 2,5 — вместо «играет-стоп-играет-стоп» одна пауза и дальше
            // ровно. Впрок — до полутора минут, но не больше объёма по
            // умолчанию: 4K-ролик на полторы минуты не уместился бы в памяти.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 30_000,
                        /* maxBufferMs = */ 90_000,
                        /* bufferForPlaybackMs = */ 2_500,
                        /* bufferForPlaybackAfterRebufferMs = */ 8_000
                    )
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ false
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
                prepare()
            }
    }

    // Wi-Fi в режиме низкой задержки, пока идёт ролик: иначе адаптер между
    // пакетами засыпает и просыпается раз в ~100 мс, и каждый запрос к NAS
    // ждёт этого пробуждения. На планшете чтение падало до 0,7 МБ/с.
    DisposableEffect(uri) {
        val wifi = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
            as? android.net.wifi.WifiManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        val lock = runCatching { wifi?.createWifiLock(mode, "fotoframe:video")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        onDispose { runCatching { lock?.release() } }
    }

    DisposableEffect(player) {
        var started = false
        var reported = false
        var rebuffers = 0
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                // Буферизация посреди ролика — в лог: так видно, сколько раз
                // сеть не успела.
                if (state == Player.STATE_BUFFERING && started) {
                    rebuffers++
                    Log.w("Video", "Видео: буферизация №$rebuffers на ${player.currentPosition / 1000} с «$title»")
                }
                if (state == Player.STATE_READY && !reported) {
                    reported = true
                    val d = player.duration
                    if (d != C.TIME_UNSET && d > 0) durationKnown(d)
                }
                if (state == Player.STATE_ENDED) ended()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && !started) {
                    started = true
                    // Размер и битрейт — чтобы сравнить со скоростью сети в логе.
                    val f = player.videoFormat
                    val info = if (f != null) {
                        val mbit = if (f.bitrate > 0) " %.0f Мбит/с".format(f.bitrate / 1e6) else ""
                        " (${f.width}×${f.height}$mbit)"
                    } else ""
                    Log.i("Video", "Видео: играет «$title»$info")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("Video", "Видео: ${error.errorCodeName} — ${error.message} («$title», $uri)")
                // Формат, который этот плеер на этом устройстве не откроет
                // никогда, — не сетевая заминка, повторять бессмысленно.
                val unsupported = error.errorCode in setOf(
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                )
                failed(unsupported)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player, paused) { player.playWhenReady = !paused }
    LaunchedEffect(player, muted) { player.volume = if (muted) 0f else 1f }

    AndroidView(
        modifier = modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                useController = false
                // Целиком, с полями — как снято. Обрезать ролик нельзя:
                // в кадре может оказаться самое важное.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
                this.player = player
            }
        },
        update = { view -> if (view.player !== player) view.player = player }
    )
}
