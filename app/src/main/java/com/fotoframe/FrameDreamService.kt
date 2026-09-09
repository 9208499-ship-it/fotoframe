package com.fotoframe

import android.service.dreams.DreamService
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.fotoframe.engine.SlideshowViewModel
import com.fotoframe.ui.SlideshowScreen
import com.fotoframe.ui.theme.FotoFrameTheme

/**
 * Заставка. Показ идёт прямо внутри неё, без запуска основного окна.
 *
 * Прежняя версия открывала MainActivity и завершалась. Побочный эффект:
 * окно с флагом «не гасить экран» оставалось на переднем плане, и приставка
 * больше никогда не уходила в заставку и не засыпала. Теперь заставка
 * живёт по правилам системы: любое нажатие на пульте её закрывает, а
 * таймер сна работает как настроен.
 *
 * Compose нуждается в трёх владельцах — жизненного цикла, состояния и
 * ViewModel. У Activity они есть из коробки, у сервиса их приходится
 * держать самому; отсюда три интерфейса в заголовке класса.
 */
class FrameDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    private var vm: SlideshowViewModel? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true

        val model = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[SlideshowViewModel::class.java]
        vm = model

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FrameDreamService)
            setViewTreeSavedStateRegistryOwner(this@FrameDreamService)
            setViewTreeViewModelStoreOwner(this@FrameDreamService)
            setContent {
                FotoFrameTheme {
                    val state by model.state.collectAsState()
                    SlideshowScreen(
                        state = state,
                        onLoadError = { model.reportLoadFailure(it) },
                        onLoadSuccess = { model.reportLoadSuccess(it) }
                    )
                }
            }
        }
        setContentView(view)
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        (application as? App)?.dreamVisible = true
        vm?.let {
            it.reindexIfStale(STARTUP_REINDEX_AFTER_MS)
            it.start()
        }
    }

    override fun onDreamingStopped() {
        vm?.stop()
        (application as? App)?.dreamVisible = false
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        vm = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val STARTUP_REINDEX_AFTER_MS = 60L * 60 * 1000
    }
}
