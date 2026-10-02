package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.udnahc.immichgallery.LocalAppActive
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.action.settings.SetSlideshowConfigAction
import com.udnahc.immichgallery.domain.model.*
import com.udnahc.immichgallery.domain.usecase.settings.GetSlideshowConfigUseCase
import com.udnahc.immichgallery.test.TestSettings
import com.udnahc.immichgallery.ui.util.*
import kotlinx.coroutines.Dispatchers
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Only external media/time/power are synthetic; owners, options, pager and controller are real. */
internal class ViewerOverlayUiFixture(
    initialConfig: SlideshowConfig = SlideshowConfig(transition = SlideshowTransition.CUT, motion = SlideshowMotion.STILL),
    fullscreenSupported: Boolean = true,
) {
    val settings = TestSettings()
    val repository = ServerConfigRepository(settings).apply { setSlideshowConfig(initialConfig) }
    val preferences = ViewerPreferencesViewModel(GetSlideshowConfigUseCase(repository), SetSlideshowConfigAction(repository))
    private val module = module { factory { preferences } }
    private val koinApplication = koinApplication { modules(module) }
    private val viewModelOwner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    val bindings = CopyOnWriteArrayList<SlideshowBinding>()
    val media = ConcurrentHashMap<SlideshowMediaRequest, ViewerMediaPresentation>()
    val settledIds = CopyOnWriteArrayList<String>()
    var now = 0L
    var appActive by mutableStateOf(true)
    var windowVisible by mutableStateOf(true)
    var placement by mutableStateOf(ViewerWindowPlacement.Floating)
    val fullscreen = ViewerFullscreenController({ placement }, { placement = it }, { windowVisible }, fullscreenSupported)
    var wakeHeld = false
        private set
    var wakeAcquires = 0
        private set
    var wakeReleases = 0
        private set
    private val environment = ViewerPlaybackEnvironment(
        createBinding = { need ->
            SlideshowBinding(need, Dispatchers.Main.immediate, { now }, automaticTicks = false).also(bindings::add)
        },
        wakeLock = object : ScreenWakeLock {
            override fun acquire() { if (!wakeHeld) { wakeHeld = true; wakeAcquires += 1 } }
            override fun release() { if (wakeHeld) { wakeHeld = false; wakeReleases += 1 } }
        },
        mediaContent = { presentation ->
            val onTap by rememberUpdatedState(presentation.onTap)
            SideEffect { presentation.playback?.request?.let { media[it] = presentation } }
            Box(Modifier.fillMaxSize().background(Color.DarkGray)
                .testTag("media:${presentation.asset.id}:${if (presentation.isCurrentPage) "selected" else "adjacent"}")
                .pointerInput(Unit) { detectTapGestures { onTap() } }) {
                Text(presentation.asset.fileName, color = Color.White)
            }
        }
    )
    val binding: SlideshowBinding get() = bindings.last()
    val state: SlideshowPlaybackState get() = binding.state.value

    @Composable
    fun Content(content: @Composable () -> Unit) {
        KoinIsolatedContext(koinApplication) {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides viewModelOwner,
                LocalAppActive provides appActive,
                LocalViewerFullscreen provides fullscreen,
                LocalViewerPlaybackEnvironment provides environment
            ) { MaterialTheme { content() } }
        }
    }

    fun ready(event: SlideshowMediaEvent = SlideshowMediaEvent.ImageReady) {
        val request = requireNotNull(state.request)
        val presentation = requireNotNull(media[request])
        presentation.onEvent(request, event)
    }

    fun advance(millis: Long) {
        now += millis
        bindings.forEach { binding -> binding.send { it.tick() } }
    }

    fun dispose() { bindings.forEach(SlideshowBinding::close); viewModelOwner.viewModelStore.clear(); fullscreen.close(); koinApplication.close() }
}

internal fun viewerFixtureAsset(id: String, type: AssetType = AssetType.IMAGE, stackCount: Int = 0): Asset = Asset(
    id, type, "$id.jpg", "2026-10-01T12:00:00Z", "fixture://$id/thumbnail", "fixture://$id/original", "fixture://$id/video", stackCount = stackCount
)

internal fun viewerFixtureDetail(id: String): AssetDetail = AssetDetail(id, "$id.jpg", "2026-10-01T12:00:00Z",
    null, null, null, null, null, null, null, 42.0, -87.0, "City", null, "Country", null, null, null, "$id description", emptyList())
