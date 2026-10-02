package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import coil3.compose.AsyncImage
import com.udnahc.immichgallery.domain.model.*
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.ViewerFullscreenSession
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import com.udnahc.immichgallery.ui.util.desktopDetailShortcuts
import immichgallery.composeapp.generated.resources.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SlideshowControls(
    state: SlideshowPlaybackState,
    onPrevious: () -> Unit,
    onPauseResume: () -> Unit,
    onNext: () -> Unit,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    onMute: () -> Unit,
    onInfo: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false
) {
    if (!state.controlsVisible) return
    Column(modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.65f))
        .navigationBarsPadding().padding(Dimens.smallSpacing).onFocusChanged { onFocusChanged(it.hasFocus) }) {
        state.failure?.let { failure ->
            Text(stringResource(when (failure) {
                SlideshowFailure.MEDIA -> Res.string.slideshow_media_failed
                SlideshowFailure.TIMEOUT -> Res.string.slideshow_timeout
                SlideshowFailure.BUCKET -> Res.string.slideshow_bucket_failed
                SlideshowFailure.MANUAL_REFRESH -> Res.string.slideshow_manual_refresh
                SlideshowFailure.ALL_VIDEOS -> Res.string.slideshow_all_videos
                SlideshowFailure.EMPTY -> Res.string.slideshow_empty
            }), color = Color.White, modifier = Modifier.padding(Dimens.smallSpacing))
        }
        FlowRow(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            TextButton(onPrevious, enabled = state.canGoPrevious) { Text(stringResource(Res.string.slideshow_previous)) }
            TextButton(onPauseResume, enabled = state.failure == null) { Text(stringResource(if (state.paused) Res.string.slideshow_resume else Res.string.slideshow_pause)) }
            TextButton(onNext) { Text(stringResource(Res.string.slideshow_next)) }
            if (state.canRetry) TextButton(onRetry) { Text(stringResource(Res.string.slideshow_retry)) }
            if (state.config?.videoPolicy == SlideshowVideoPolicy.PLAY) TextButton(onMute) { Text(stringResource(if (state.config.muted) Res.string.slideshow_unmute else Res.string.slideshow_mute)) }
            if (state.asset != null) TextButton(onInfo) { Text(stringResource(Res.string.detail_info)) }
            if (onToggleFullscreen != null) TextButton(onToggleFullscreen) { Text(stringResource(if (isFullscreen) Res.string.viewer_exit_fullscreen else Res.string.viewer_enter_fullscreen)) }
            TextButton(onExit) { Text(stringResource(Res.string.slideshow_exit)) }
        }
    }
}

@Composable
internal fun SlideshowChrome(
    binding: SlideshowBinding,
    state: SlideshowPlaybackState,
    getAssetDetail: suspend (String) -> Result<AssetDetail>,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
    fullscreenSession: ViewerFullscreenSession? = null,
    preferences: ViewerPreferencesViewModel = koinViewModel()
) {
    val scope = rememberCoroutineScope()
    val latestDetail by rememberUpdatedState(getAssetDetail)
    val captions = remember { SlideshowCaptionLoader(scope) { latestDetail(it) } }
    val caption by captions.state.collectAsState()
    LaunchedEffect(state.request, state.config?.captions) { captions.select(state.request, state.config?.captions == true) }
    DisposableEffect(captions) { onDispose(captions::close) }
    Column(modifier) {
        val currentCaption = caption?.takeIf { state.config?.captions == true && it.assetId == state.request?.assetId }
        if (currentCaption != null) SlideshowCaption(currentCaption)
        SlideshowControls(state,
            onPrevious = { binding.send { it.previous() } }, onPauseResume = { binding.send { it.togglePause() } },
            onNext = { binding.send { it.next() } }, onExit = { binding.send { it.stop() } },
            onRetry = { binding.send { it.retry() } }, onMute = { binding.toggleMute(preferences) },
            onInfo = onInfo, onFocusChanged = { focused -> binding.send { it.setFocused(focused) } },
            onToggleFullscreen = fullscreenSession?.takeIf { it.isSupported }?.let { { it.toggle() } },
            isFullscreen = fullscreenSession?.isFullscreen == true)
    }
}

@Composable
private fun SlideshowCaption(caption: SlideshowCaptionContent?) {
    caption ?: return
    val localDate = remember(caption.dateTime) { caption.dateTime?.let { runCatching { Instant.parse(it).toLocalDateTime(TimeZone.currentSystemDefault()) }.getOrNull() } }
    Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(Dimens.screenPadding)) {
        localDate?.let { Text(stringResource(Res.string.detail_date_time, localizedMonthName(it.month.ordinal + 1), it.day, it.year,
            it.hour.toString().padStart(2, '0'), it.minute.toString().padStart(2, '0')), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        caption.location?.let { Text(it, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        caption.description?.let { Text(it, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
internal fun SlideshowFadeLayer(transition: SlideshowPagerTransition) {
    val asset = transition.outgoing ?: return
    AsyncImage(model = if (asset.type == AssetType.VIDEO) asset.thumbnailUrl else asset.originalUrl,
        contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = transition.outgoingAlpha.value }.background(Color.Black))
}

/** Source removal must retain an accessible exit even when there are no pager pages. */
@Composable
internal fun SlideshowEmptyViewer(
    binding: SlideshowBinding, state: SlideshowPlaybackState,
    preferences: ViewerPreferencesViewModel = koinViewModel(), onDismiss: () -> Unit
) {
    val exit = { binding.send { it.stop() }; onDismiss() }
    PlatformBackHandler(enabled = true, onBack = exit)
    Box(Modifier.fillMaxSize().background(Color.Black).desktopDetailShortcuts(enabled = true, onPrev = {}, onNext = {}, onDismiss = exit, onToggleSlideshow = {}, onInfo = {})) {
        TextButton(exit, modifier = Modifier.align(Alignment.Center)) { Text(stringResource(Res.string.back)) }
        if (state.config != null) SlideshowControls(state.copy(asset = null),
            onPrevious = { binding.send { it.previous() } }, onPauseResume = { binding.send { it.togglePause() } },
            onNext = { binding.send { it.next() } }, onExit = exit, onRetry = { binding.send { it.retry() } },
            onMute = { binding.toggleMute(preferences) }, onInfo = {}, onFocusChanged = {},
            modifier = Modifier.align(Alignment.BottomCenter))
    }
}
