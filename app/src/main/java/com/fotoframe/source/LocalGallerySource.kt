package com.fotoframe.source

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import android.app.RecoverableSecurityException
import android.net.Uri
import com.fotoframe.data.db.Photo
import com.fotoframe.source.DeleteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Снимки с самого устройства и с подключённой флешки.
 * На приставке это обычно USB-накопитель с фотоархивом.
 */
class LocalGallerySource(private val context: Context) : MediaSource {

    override val id = "local"
    override val title = "Память устройства"

    /**
     * Без разрешения MediaStore молча возвращает пустой курсор, и обход
     * выглядел бы как «все файлы исчезли» — индекс удалил бы историю по
     * всей локальной коллекции. Поэтому готовность — это наличие разрешения.
     */
    override suspend fun isReady(): Boolean = hasMediaPermission(context)

    companion object {
        /**
         * Есть ли доступ к галерее. На Android 14 пользователь может дать
         * доступ только к выбранным снимкам — тогда система выдаёт другое
         * разрешение, и его надо принимать как «да», а не как отказ.
         */
        fun hasMediaPermission(context: Context): Boolean {
            fun granted(p: String) =
                ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    granted(Manifest.permission.READ_MEDIA_IMAGES)
                else ->
                    granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        private const val BATCH = 300
    }

    override suspend fun listFolders(parent: String?): List<Folder> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, Folder>()
            val projection = arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val bucketId = c.getString(idCol) ?: continue
                    val name = c.getString(nameCol) ?: continue
                    out.putIfAbsent(bucketId, Folder(bucketId, name))
                }
            }
            out.values.toList()
        }

    override suspend fun scan(root: String, onBatch: suspend (List<RemoteItem>) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.LATITUDE,
                MediaStore.Images.Media.LONGITUDE
            )

            val selection = if (root.isNotEmpty() && root != "*") {
                "${MediaStore.Images.Media.BUCKET_ID} = ?"
            } else null
            val args = if (selection != null) arrayOf(root) else null

            val batch = ArrayList<RemoteItem>(BATCH)

            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            ) ?: return@withContext false

            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                // LATITUDE/LONGITUDE на Android 10+ не заполняются — берём мягко.
                val latCol = c.getColumnIndex(MediaStore.Images.Media.LATITUDE)
                val lonCol = c.getColumnIndex(MediaStore.Images.Media.LONGITUDE)

                while (c.moveToNext()) {
                    val mediaId = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId
                    )

                    // DATE_TAKEN бывает нулевой у скачанных файлов —
                    // тогда дата изменения ближе всего к истине, но
                    // настоящей датой съёмки она не считается.
                    val dateTaken = c.getLong(takenCol)
                    val exact = dateTaken > 0
                    val taken = if (exact) dateTaken else c.getLong(modifiedCol) * 1000L

                    batch += RemoteItem(
                        remoteId = mediaId.toString(),
                        uri = uri.toString(),
                        displayName = c.getString(nameCol) ?: "",
                        albumName = c.getString(bucketCol),
                        takenAt = taken,
                        takenAtExact = exact,
                        width = c.getInt(wCol),
                        height = c.getInt(hCol),
                        sizeBytes = c.getLong(sizeCol),
                        latitude = if (latCol >= 0) c.getDouble(latCol).takeIf { it != 0.0 } else null,
                        longitude = if (lonCol >= 0) c.getDouble(lonCol).takeIf { it != 0.0 } else null
                    )

                    if (batch.size >= BATCH) {
                        onBatch(batch.toList())
                        batch.clear()
                    }
                }
            }

            if (batch.isNotEmpty()) onBatch(batch.toList())
            true
        }

    override suspend fun resolveDisplayUrl(photo: Photo): String = photo.uri

    /**
     * Удаление файла на самом устройстве.
     *
     * До Android 11 приложение с разрешением на запись стирает файл само.
     * С Android 11 чужие снимки удаляются только после системного диалога:
     * MediaStore возвращает PendingIntent, который должна показать
     * Activity. В заставке показать его негде — там удаление недоступно.
     */
    override suspend fun delete(photo: Photo): DeleteResult = withContext(Dispatchers.IO) {
        val uri = Uri.parse(photo.uri)

        // Android 11 и выше: система показывает свой диалог по готовому
        // запросу, отдельные разрешения для этого не нужны.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return@withContext try {
                DeleteResult.NeedsConfirmation(
                    MediaStore.createDeleteRequest(context.contentResolver, listOf(uri))
                )
            } catch (e: Exception) {
                DeleteResult.Failed("Система не дала запросить удаление: ${e.message}")
            }
        }

        try {
            val rows = context.contentResolver.delete(uri, null, null)
            if (rows > 0) {
                DeleteResult.Done("удалён с устройства")
            } else {
                DeleteResult.Failed("Система не дала удалить файл")
            }
        } catch (e: SecurityException) {
            // Android 10: чужой файл удаляется только с согласия
            // пользователя, и система прикладывает готовый запрос прямо к
            // исключению. Без этой ветки любое такое удаление выглядело
            // как «нет прав», хотя дело не в разрешениях: доступ к памяти
            // на удаление чужих снимков права не даёт вовсе.
            val recoverable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                e as? RecoverableSecurityException
            } else {
                null
            }

            if (recoverable != null) {
                DeleteResult.NeedsConfirmation(recoverable.userAction.actionIntent)
            } else {
                DeleteResult.Failed(
                    "Система не дала удалить файл: ${e.message ?: "нет прав"}"
                )
            }
        }
    }
}
