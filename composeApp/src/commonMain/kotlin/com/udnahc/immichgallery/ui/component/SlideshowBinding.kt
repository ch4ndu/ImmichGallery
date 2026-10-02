package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.*
import com.udnahc.immichgallery.AppLifecycle
import com.udnahc.immichgallery.domain.model.*
import com.udnahc.immichgallery.ui.util.rememberScreenWakeLock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.TimeSource

/** One consumer owns decisions; source snapshots conflate and ticks never queue. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SlideshowBinding(
    requestBucket: (String, Boolean) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    nowMillis: (() -> Long)? = null,
    private val automaticTicks: Boolean = true
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<(SlideshowPlaybackController) -> Unit>(64)
    private val environments = Channel<Boolean>(Channel.CONFLATED)
    private val progressEvents = Channel<Pair<SlideshowMediaRequest, SlideshowMediaEvent.Progress>>(Channel.CONFLATED)
    private val sources = Channel<() -> List<SlideshowSourceItem>>(Channel.CONFLATED)
    private val origin = TimeSource.Monotonic.markNow()
    private val controller = SlideshowPlaybackController(nowMillis ?: { origin.elapsedNow().inWholeMilliseconds }, requestBucket)
    val state: StateFlow<SlideshowPlaybackState> = controller.state
    val uiState = state.map { it.copy(motionProgress = 0f) }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, SlideshowPlaybackState())

    init {
        scope.launch {
            try {
                while (isActive) {
                    select<Unit> {
                        environments.onReceive { controller.setEnvironment(it) }
                        sources.onReceive { controller.updateSource(it()) }
                        progressEvents.onReceive { (request, event) -> controller.mediaEvent(request, event) }
                        commands.onReceive { it(controller) }
                        if (automaticTicks) onTimeout(33) { }
                    }
                    controller.tick()
                }
            } finally { controller.close() }
        }
    }

    fun source(value: () -> List<SlideshowSourceItem>) { sources.trySend(value) }
    fun send(command: (SlideshowPlaybackController) -> Unit) {
        if (!commands.trySend(command).isSuccess) scope.launch { commands.send(command) }
    }
    fun toggleMute(preferences: ViewerPreferencesViewModel) {
        send { owner ->
            owner.toggleMute()
            owner.state.value.config?.muted?.let { muted ->
                preferences.setSlideshowConfig(preferences.slideshowConfig.value.copy(muted = muted))
            }
        }
    }
    fun environment(active: Boolean) { environments.trySend(active) }
    fun mediaEvent(request: SlideshowMediaRequest, event: SlideshowMediaEvent) {
        if (event is SlideshowMediaEvent.Progress) progressEvents.trySend(request to event)
        else send { it.mediaEvent(request, event) }
    }
    fun close() { scope.cancel() }
}

@Composable
internal fun rememberSlideshowBinding(
    environmentActive: Boolean,
    onBucketNeeded: (String) -> Unit = {},
    onRetryBucket: (String) -> Unit = {}
): SlideshowBinding {
    val latestNeed by rememberUpdatedState(onBucketNeeded)
    val latestRetry by rememberUpdatedState(onRetryBucket)
    val environment = LocalViewerPlaybackEnvironment.current
    val binding = remember(environment) {
        val need: (String, Boolean) -> Unit = { bucket, retry -> if (retry) latestRetry(bucket) else latestNeed(bucket) }
        environment?.createBinding?.invoke(need) ?: SlideshowBinding(need)
    }
    val foreground by AppLifecycle.isForeground.collectAsState()
    val state by binding.uiState.collectAsState()
    val wakeLock = environment?.wakeLock ?: rememberScreenWakeLock()
    LaunchedEffect(binding, foreground, environmentActive) {
        binding.environment(foreground && environmentActive)
    }
    LaunchedEffect(state.keepAwake, foreground, environmentActive) {
        if (state.keepAwake && foreground && environmentActive) wakeLock.acquire() else wakeLock.release()
    }
    DisposableEffect(binding, wakeLock) {
        onDispose { binding.close(); wakeLock.release() }
    }
    return binding
}

internal class SlideshowPagerTransition {
    val outgoingAlpha = Animatable(0f)
    var outgoing by mutableStateOf<Asset?>(null)
}

@Composable
internal fun BindSlideshowPager(
    binding: SlideshowBinding,
    state: SlideshowPlaybackState,
    pager: PagerState,
    currentAsset: Asset?
): SlideshowPagerTransition {
    val transition = remember { SlideshowPagerTransition() }
    val latestCurrent by rememberUpdatedState(currentAsset)
    LaunchedEffect(state.navigationId, state.paused, state.config != null) {
        if (state.config == null || !state.transitioning || pager.pageCount == 0) {
            transition.outgoing = null
            transition.outgoingAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        val id = state.navigationId
        val page = state.page.coerceIn(0, pager.pageCount - 1)
        try {
            when (if (state.paused) SlideshowTransition.CUT else state.config.transition) {
                SlideshowTransition.SLIDE -> pager.animateScrollToPage(page)
                SlideshowTransition.CUT -> pager.scrollToPage(page)
                SlideshowTransition.FADE -> {
                    transition.outgoing = latestCurrent
                    transition.outgoingAlpha.snapTo(1f)
                    pager.scrollToPage(page)
                    transition.outgoingAlpha.animateTo(0f, tween(350))
                }
            }
            binding.send { it.navigationFinished(id) }
        } finally {
            transition.outgoing = null
        }
    }
    return transition
}

internal fun staticSlideshowSource(assets: List<Asset>): List<SlideshowSourceItem> =
    assets.map { SlideshowSourceItem(SlideshowSlot.Asset(it.id), it) }
