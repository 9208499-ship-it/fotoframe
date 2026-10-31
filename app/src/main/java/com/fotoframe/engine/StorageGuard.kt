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
 *
 * Свежие файлы — моложе [PROTECT_MS] — не удаляются никогда: это кадр на
 * экране и кадры очереди. Раньше при нехватке места сторож стирал всё
 * подряд, включая только что скачанный следующий кадр, и тот уже не
 * открывался: «не удалось загрузить» по всем источникам разом, пока место
 * не освободится, — на практике до перезагрузки приставки.
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
        if (root.usableSpace - need >= KEEP_FREE) {
            lowSpaceMb = -1
            return
        }

        val files = DIRS.map { File(root, it) }
            // Журнал кэша изображений не трогаем — без него кэш пересобирается целиком.
            .flatMap { d -> d.walkTopDown().filter { it.isFile && !it.name.startsWith("journal") }.toList() }
            .filterNot { isProtected(it) }
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
        val free = root.usableSpace
        lowSpaceMb = if (free - need < KEEP_FREE) free / 1048576 else -1
        if (removed > 0 || lowSpaceMb >= 0) {
            Log.w(
                TAG,
                "Мало места: освобождено %.0f МБ (%d файлов), свободно %.0f МБ".format(
                    freed / 1048576.0, removed, free / 1048576.0
                )
            )
        }
    }

    /**
     * Свободно на устройстве, МБ, если места не хватает даже после чистки
     * кэша: его заняло что-то другое. -1 — места достаточно. Показ пишет
     * это в сообщение об ошибке — иначе причину не угадать.
     */
    @Volatile
    var lowSpaceMb: Long = -1
        private set

    /** Файл свежий — на экране или в очереди, удалять нельзя. */
    fun isProtected(f: File): Boolean =
        System.currentTimeMillis() - f.lastModified() < PROTECT_MS

    /** Столько живёт защита свежего файла: с запасом на очередь из пяти кадров при долгом интервале. */
    private const val PROTECT_MS = 30L * 60 * 1000

    /** Нижняя граница предела — меньше кэш бессмыслен. */
    private const val MIN_LIMIT = 30L * 1024 * 1024
}
