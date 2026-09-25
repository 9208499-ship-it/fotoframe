package com.fotoframe

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ExifOrientationPolicy
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.fotoframe.data.db.AppDatabase
import com.fotoframe.data.prefs.SettingsStore
import com.fotoframe.engine.ContentFilter
import com.fotoframe.engine.FileNameDate
import com.fotoframe.engine.IndexWorker
import com.fotoframe.source.SourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application(), ImageLoaderFactory {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var sources: SourceRegistry
        private set

    /**
     * Рамка на экране. Фоновая дообработка по этому признаку уступает
     * сеть показу — иначе следующий кадр с NAS не успевал за интервалом.
     */
    @Volatile
    var uiVisible: Boolean = false
        private set

    /** Заставка на экране — для неё нет Activity, она сообщает о себе сама. */
    @Volatile
    var dreamVisible: Boolean = false
        set(value) {
            field = value
            uiVisible = value || startedActivities > 0
        }

    @Volatile
    private var startedActivities = 0

    /** Фоновые задачи уровня приложения: разовые дозаписи после обновлений. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.get(this)
        settingsStore = SettingsStore(this)
        sources = SourceRegistry(this, settingsStore) { database.photoDao() }
        IndexWorker.schedule(this)
        trackForeground()
        runOneTimeMigrations()
    }

    private fun trackForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                uiVisible = true
            }
            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                uiVisible = startedActivities > 0 || dreamVisible
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Разовые правки индекса после обновлений приложения. На свежей
     * установке править нечего — просто помечаем всё выполненным.
     */
    private fun runOneTimeMigrations() {
        appScope.launch {
            val sp = getSharedPreferences("migrations", MODE_PRIVATE)
            val dao = database.photoDao()

            if (dao.countAll() == 0) {
                sp.edit()
                    .putBoolean("enrich_v2", true)
                    .putBoolean("meta_v3", true)
                    .putBoolean("geo_v4", true)
                    .putBoolean("date_v5", true)
                    .putBoolean("filter_v5", true)
                    .putBoolean("place_v6", true)
                    .putBoolean("name_date_v7", true)
                    .putBoolean("dims_v8", true)
                    .putBoolean("faces_v9", true)
                    .putBoolean("dims_v10", true)
                    .putBoolean("places_v11", true)
                    .putBoolean("signals_v12", true)
                    .putBoolean("sources_v14", true)
                    .apply()
                return@launch
            }

            // enrich_v2: облачным и сетевым снимкам без проверки ставилось
            // «лиц нет», EXIF не читался вовсе.
            if (!sp.getBoolean("enrich_v2", false)) {
                runCatching { dao.resetEnrichment() }
                    .onSuccess { sp.edit().putBoolean("enrich_v2", true).apply() }
            }
            // meta_v3: EXIF читался только вместе с поиском лиц.
            if (!sp.getBoolean("meta_v3", false)) {
                runCatching { dao.resetMetadata() }
                    .onSuccess { sp.edit().putBoolean("meta_v3", true).apply() }
            }
            // geo_v4: вторая попытка по целому файлу для снимков без координат.
            if (!sp.getBoolean("geo_v4", false)) {
                runCatching { dao.resetMetadataWithoutCoords() }
                    .onSuccess { sp.edit().putBoolean("geo_v4", true).apply() }
            }
            // date_v5: появился признак настоящей даты съёмки. У всех старых
            // записей он снят — перечитываем EXIF, чтобы поставить его там,
            // где дата в файле есть. Остальное дозаполнит обход индекса.
            if (!sp.getBoolean("date_v5", false)) {
                runCatching { dao.resetMetadataWithoutExactDate() }
                    .onSuccess { sp.edit().putBoolean("date_v5", true).apply() }
            }
            // place_v6: пустые «места нет» от оффлайнового системного
            // геокодера — на перепроверку. Только снимки с координатами
            // задеты, остальным и сбрасывать нечего.
            if (!sp.getBoolean("place_v6", false)) {
                runCatching { dao.resetEmptyPlaces() }
                    .onSuccess { sp.edit().putBoolean("place_v6", true).apply() }
            }
            // name_date_v7: даты из имён файлов по всей коллекции. Разбор
            // EXIF по сети занимает часы, а имя вида 20260523_165611.heic
            // даёт дату мгновенно и локально.
            if (!sp.getBoolean("name_date_v7", false)) {
                runCatching { datesFromNames(dao) }
                    .onSuccess { sp.edit().putBoolean("name_date_v7", true).apply() }
            }
            // dims_v8: размеры кадра стали храниться в индексе. У снимков,
            // разобранных прежними версиями, их нет — отправляем на
            // повторное чтение заголовка.
            if (!sp.getBoolean("dims_v8", false)) {
                runCatching { dao.resetMetadataWithoutDimensions() }
                    .onSuccess { sp.edit().putBoolean("dims_v8", true).apply() }
            }
            // faces_v9: детектор не учитывал ориентацию EXIF. У повёрнутых
            // снимков лица либо не находились, либо координаты попадали не
            // в ту систему. Проверяем всё заново; проход идёт последним и
            // показу не мешает.
            if (!sp.getBoolean("faces_v9", false)) {
                runCatching { dao.resetFaces() }
                    .onSuccess { sp.edit().putBoolean("faces_v9", true).apply() }
            }
            // dims_v10: подготовка кадра помечала HEIC обработанными по
            // пустому заголовку — без размеров, даты и координат. Такие
            // записи возвращаются в очередь; теперь EXIF читается из
            // скачанного для показа файла целиком.
            if (!sp.getBoolean("dims_v10", false)) {
                runCatching { dao.resetMetaWithoutDims() }
                    .onSuccess { sp.edit().putBoolean("dims_v10", true).apply() }
            }
            // places_v11: в подписи попадали названия на нечитаемых
            // письменностях — «อ.กะทู้» вместо «Пхукет». Сбрасываем
            // подписи, чтобы они пересчитались по новым правилам; сами
            // координаты при этом сохранены, повторных чтений EXIF не
            // будет, только обращения к геокодеру.
            if (!sp.getBoolean("places_v11", false)) {
                runCatching { dao.resetPlaces() }
                    .onSuccess { sp.edit().putBoolean("places_v11", true).apply() }
            }
            // signals_v12: появились отпечаток картинки (для отсева серий)
            // и заметность сцены (наезд без лиц). И то и другое считается
            // в проходе поиска лиц — отправляем все снимки в него заново.
            // Кадр на экране получает своё сразу, остальные — постепенно.
            if (!sp.getBoolean("signals_v12", false)) {
                runCatching { dao.resetFaces() }
                    .onSuccess { sp.edit().putBoolean("signals_v12", true).apply() }
            }
            // sources_v14 (было v13; переименовано, потому что у одного из
            // тестировщиков пересчёт прошёл со старым фильтром и поставил
            // отметку «сделано»): фильтр стал исключать снимки отключённых
            // источников, но пересчитывался только при смене настроек —
            // а если источник отключили до обновления, смены не было, и
            // его снимки так и шли в показ чёрными экранами. Пересчёт
            // один раз здесь.
            if (!sp.getBoolean("sources_v14", false)) {
                runCatching { ContentFilter(dao).apply(settingsStore.settings.first()) }
                    .onSuccess { sp.edit().putBoolean("sources_v14", true).apply() }
            }
            // filter_v5: фильтр переехал из SQL в Kotlin и стал понимать
            // кириллицу — пересчитываем допуск по всей коллекции.
            if (!sp.getBoolean("filter_v5", false)) {
                runCatching { ContentFilter(dao).apply(settingsStore.settings.first()) }
                    .onSuccess { sp.edit().putBoolean("filter_v5", true).apply() }
            }
        }
    }

    /**
     * Проставляет дату съёмки, разобранную из имени файла, всем записям,
     * у которых настоящей даты ещё нет. Идёт страницами: коллекция может
     * быть в десятки тысяч записей, и держать их все в памяти незачем.
     */
    private suspend fun datesFromNames(dao: com.fotoframe.data.db.PhotoDao) {
        var offset = 0
        var updated = 0

        while (true) {
            val rows = dao.namesWithoutExactDate(PAGE, offset)
            if (rows.isEmpty()) break

            var parsedInPage = 0
            for (row in rows) {
                val parsed = FileNameDate.parse(row.displayName)
                if (parsed != null) {
                    dao.setTakenAt(row.id, parsed)
                    parsedInPage++
                }
            }
            updated += parsedInPage

            // Проставленные записи выпадают из выборки, поэтому смещение
            // сдвигаем только на те, что остались без даты — иначе страницы
            // начали бы перескакивать через неразобранные имена.
            offset += rows.size - parsedInPage
            if (rows.size < PAGE) break
        }

        android.util.Log.i("App", "Дат из имён файлов проставлено: $updated")
    }

    /**
     * Кэш на диске избавляет от повторного скачивания: на втором круге
     * показа облачные снимки открываются мгновенно. Полгигабайта хватает
     * на несколько тысяч превью.
     */
    private companion object {
        const val PAGE = 2000
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { sources.httpClient() }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images"))
                    .maxSizeBytes(512L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            // Ориентацию из EXIF применять для всех форматов. По умолчанию
            // загрузчик пропускает HEIC/HEIF — там ориентацию дороже
            // читать, — и снимки с телефона, снятые вертикально, показывались
            // боком. Цена: чуть больше чтения при первом раскодировании.
            .bitmapFactoryExifOrientationPolicy(ExifOrientationPolicy.RESPECT_ALL)
            .crossfade(false) // сменой кадров управляет слой переходов
            .build()
}
