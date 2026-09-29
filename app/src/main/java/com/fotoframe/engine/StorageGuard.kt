package com.fotoframe.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Сторож места на устройстве.
 *
 * Рамка хранит скачанное в нескольких кэшах: снимки с сетевой папки,
 * картины музеев, кэш загрузчика изображений. Раньше у каждого был
 * фиксированный предел — вместе до полутора гигабайт. На приставке с
 * Google TV из восьми гигабайт свободно часто меньше гигабайта: за
 * полчаса показа кэш съедал всё место, дальше не записывался ни один
 * файл, и отказывали все источники разом — облако, сеть, музеи. Кэш
 * переживал перезапуск, поэтому перезапуск не помогал.
 *
 * Теперь предел каждого кэша зависит от свободного места ([limitFor]), а
 * перед каждой записью сторож проверяет, что после неё останется
 * [KEEP_FREE]; если нет — удаляет самые давние файлы из всех кэшей рамки
 * ([ensureRoom]).
 */
object StorageGuard {

    private const val TAG = "Storage"

    /** Сколько оставлять свободным на устройстве в любом случае. */
    const val KEEP_FREE = 500L * 1024 * 1024

    /** Папки кэша рамки, из которых можно удалять. */
    private val DIRS = listOf("smb", "museum", "images")

    /**
     * Предел для одного кэша: не больше [ceiling] и не больше пятой части
     * того, чем рамка может распоряжаться (свободное место плюс уже занятое
     * этим кэшем, за вычетом [KEEP_FREE]). На свободной приставке — как
     * раньше, на заполненной — десятки мегабайт.
     */
    fun limitFor(dir: File, ceiling: Long): Long {
        val used = dir.listFiles()?.sumOf { it.length() } ?: 0L
        val available = (dir.usableSpace + used - KEEP_FREE).coerceAtLeast(0L)
        return minOf(ceiling, available / 5).coerceAtLeast(MIN_LIMIT)
    }

    /**
     * Перед записью [need] байт: если после неё свободного останется меньше
     * [KEEP_FREE], удаляем самые давние файлы из всех кэшей рамки, пока
     * место не появится или удалять станет нечего.
     */
    @Synchronized
    fun ensureRoom(context: Context, need: Long = 20L * 1024 * 1024) {
        val root = context.cacheDir
        if (root.usableSpace - need >= KEEP_FREE) return

        val files = DIRS.map { File(root, it) }
            // Журнал кэша изображений не трогаем — без него кэш пересобирается целиком.
            .flatMap { d -> d.walkTopDown().filter { it.isFile && !it.name.startsWith("journal") }.toList() }
            .sortedBy { it.lastModified() }

        var freed = 0L
        var removed = 0
        for (f in files) {
            if (root.usableSpace - need >= KEEP_FREE) break
            val len = f.length()
            if (f.delete()) {
                freed += len
                removed++
            }
        }
        Log.w(
            TAG,
            "Мало места: освобождено %.0f МБ (%d файлов), свободно %.0f МБ".format(
                freed / 1048576.0, removed, root.usableSpace / 1048576.0
            )
        )
    }

    /** Нижняя граница предела — меньше кэш бессмыслен. */
    private const val MIN_LIMIT = 30L * 1024 * 1024
}
