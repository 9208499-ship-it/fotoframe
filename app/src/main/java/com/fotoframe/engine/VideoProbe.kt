package com.fotoframe.engine

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.fotoframe.data.db.Photo
import com.fotoframe.source.SourceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Длительность ролика — из самого файла, без скачивания целиком.
 *
 * Система знает длительность только для видео на устройстве; у сетевой
 * папки и Диска её в списке файлов нет. MediaMetadataRetriever читает
 * лишь заголовок ролика: с NAS — нужные куски через [SmbMediaDataSource],
 * с Диска — запросами диапазонов по прямой ссылке. Это доли секунды.
 */
class VideoProbe(private val context: Context, private val sources: SourceRegistry) {

    /** Длительность, мс; null — узнать не удалось (формат, сеть). */
    suspend fun durationMs(photo: Photo): Long? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        var smbData: SmbMediaDataSource? = null
        try {
            when (photo.sourceId) {
                "smb" -> {
                    val (file, length) = sources.smb.openStream(photo.uri) ?: return@withContext null
                    smbData = SmbMediaDataSource(sources.smb, file, length)
                    r.setDataSource(smbData)
                }
                "yandex" -> {
                    val url = sources.yandex.resolveVideoUri(photo)
                    r.setDataSource(url, HashMap())
                }
                else -> r.setDataSource(context, Uri.parse(photo.uri))
            }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.takeIf { it > 0 }
        } catch (e: Throwable) {
            e.rethrowIfCancelled()
            Log.w(TAG, "Длительность «${photo.displayName}»: ${e.message}")
            null
        } finally {
            runCatching { r.release() }
            runCatching { smbData?.close() }
        }
    }

    private companion object {
        const val TAG = "Video"
    }
}

/** Файл на сетевой папке — источником данных для MediaMetadataRetriever. */
private class SmbMediaDataSource(
    private val smb: com.fotoframe.source.SmbSource,
    private val file: com.hierynomus.smbj.share.File,
    private val length: Long
) : MediaDataSource() {

    @Volatile
    private var closed = false

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed || position >= length) return -1
        if (size == 0) return 0
        val want = minOf(size.toLong(), length - position).toInt()
        return file.read(buffer, position, offset, want).let { if (it <= 0) -1 else it }
    }

    override fun getSize(): Long = length

    override fun close() {
        if (closed) return
        closed = true
        smb.closeStream(file)
    }
}
