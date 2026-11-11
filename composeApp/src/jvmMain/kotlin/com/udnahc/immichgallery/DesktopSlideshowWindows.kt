package com.udnahc.immichgallery

import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.udnahc.immichgallery.domain.model.SlideshowWindowRequest
import com.udnahc.immichgallery.ui.component.LocalSlideshowWindowHost
import com.udnahc.immichgallery.ui.screen.slideshow.SlideshowWindowContent
import com.udnahc.immichgallery.ui.screen.slideshow.SlideshowWindowViewModel
import com.udnahc.immichgallery.ui.theme.ImmichGalleryTheme
import com.udnahc.immichgallery.ui.util.*
import immichgallery.composeapp.generated.resources.*
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource
import org.koin.core.Koin

internal class DesktopWindowOwner : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

internal class DesktopSlideshowEntry(
    val request: SlideshowWindowRequest,
    val owner: DesktopWindowOwner,
    val viewModel: SlideshowWindowViewModel
)

/** Process host; only admitted sources enter the native-window registry. */
internal class DesktopSlideshowWindows(
    private val scope: CoroutineScope,
    private val koin: Koin,
    private val exit: () -> Unit
) {
    val windows = mutableStateListOf<DesktopSlideshowEntry>()
    private val launches = mutableSetOf<Job>()
    var libraryVisible by mutableStateOf(true)
        private set
    var libraryActivation by mutableIntStateOf(0)
        private set
    private var quitting = false

    fun launch(request: SlideshowWindowRequest) {
        if (quitting) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val owner = DesktopWindowOwner()
            val viewModel = koin.get<SlideshowWindowViewModel>()
            owner.viewModelStore.put("slideshow", viewModel)
            val entry = DesktopSlideshowEntry(request, owner, viewModel)
            try {
                viewModel.start(request) {
                    withContext(Dispatchers.Main) {
                        currentCoroutineContext().ensureActive()
                        if (!quitting) windows += entry else viewModel.close()
                    }
                }
                viewModel.join()
            } finally {
                viewModel.close()
                // Mutation joins source work, never Compose disposal. The registered job
                // ends independently; this host subsequently clears its UI/store resources.
                withContext(NonCancellable + Dispatchers.Main) {
                    viewModel.join()
                    windows.remove(entry)
                    owner.viewModelStore.clear()
                }
            }
        }
        launches += job
        job.invokeOnCompletion {
            // Scope launches and native callbacks run on the same UI dispatcher.
            launches.remove(job)
            exitIfLastWindow()
        }
        job.start()
    }

    fun closeLibrary() {
        libraryVisible = false
        AppLifecycle.setForeground(false)
        exitIfLastWindow()
    }

    fun showLibrary() {
        if (quitting) return
        libraryVisible = true
        libraryActivation += 1
    }

    private fun exitIfLastWindow() {
        if (!quitting && !libraryVisible && windows.isEmpty() && launches.isEmpty()) exit()
    }

    fun quit() {
        if (quitting) return
        quitting = true
        windows.forEach { it.viewModel.close() }
        scope.launch {
            val pending = launches.toList()
            pending.forEach { it.cancel() }
            pending.joinAll()
            exit()
        }
    }
}

@Composable
internal fun ApplicationScope.DesktopSlideshowWindowHosts(host: DesktopSlideshowWindows) {
    host.windows.toList().forEach { entry ->
        key(entry) {
            val state = rememberWindowState()
            Window(
                onCloseRequest = entry.viewModel::close,
                title = stringResource(Res.string.detail_slideshow),
                state = state
            ) {
                DesktopLibraryMenu(host)
                DesktopWindowEnvironment(window, state) { fullscreen ->
                    AppKoin {
                        ImmichGalleryTheme {
                            CompositionLocalProvider(
                                LocalViewModelStoreOwner provides entry.owner,
                                LocalSlideshowWindowHost provides host::launch
                            ) {
                                SlideshowWindowContent(
                                    request = entry.request,
                                    viewModel = entry.viewModel,
                                    eligible = fullscreen.isWindowVisibleAndNotMinimized,
                                    onClose = entry.viewModel::close
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FrameWindowScope.DesktopLibraryMenu(host: DesktopSlideshowWindows) {
    val file = stringResource(Res.string.desktop_file)
    val showLibrary = stringResource(Res.string.desktop_show_library)
    val quit = stringResource(Res.string.desktop_quit)
    MenuBar {
        Menu(file) {
            Item(showLibrary, onClick = host::showLibrary)
            Separator()
            Item(quit, onClick = host::quit)
        }
    }
}

/** A distinct native placement/lease controller per window, with no focus side effects. */
@Composable
internal fun DesktopWindowEnvironment(
    window: ComposeWindow,
    state: WindowState,
    content: @Composable (ViewerFullscreenController) -> Unit
) {
    var visible by remember(window) { mutableStateOf(window.isVisible) }
    val fullscreen = remember(window, state) {
        ViewerFullscreenController(
            readPlacement = {
                when (state.placement) {
                    WindowPlacement.Floating -> ViewerWindowPlacement.Floating
                    WindowPlacement.Maximized -> ViewerWindowPlacement.Maximized
                    WindowPlacement.Fullscreen -> ViewerWindowPlacement.Fullscreen
                }
            },
            writePlacement = {
                state.placement = when (it) {
                    ViewerWindowPlacement.Floating -> WindowPlacement.Floating
                    ViewerWindowPlacement.Maximized -> WindowPlacement.Maximized
                    ViewerWindowPlacement.Fullscreen -> WindowPlacement.Fullscreen
                }
            },
            readWindowVisibility = { visible && !state.isMinimized }
        )
    }
    DisposableEffect(window, fullscreen) {
        val listener = object : ComponentAdapter() {
            override fun componentShown(event: ComponentEvent?) { visible = true }
            override fun componentHidden(event: ComponentEvent?) { visible = false }
        }
        window.addComponentListener(listener)
        visible = window.isVisible
        onDispose {
            window.removeComponentListener(listener)
            fullscreen.close()
        }
    }
    CompositionLocalProvider(LocalViewerFullscreen provides fullscreen) { content(fullscreen) }
}
