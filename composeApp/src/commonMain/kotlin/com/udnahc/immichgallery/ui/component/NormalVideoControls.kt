package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import chaintech.videoplayer.host.MediaPlayerEvent
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.ic_pause
import immichgallery.composeapp.generated.resources.ic_play
import immichgallery.composeapp.generated.resources.ic_volume_off
import immichgallery.composeapp.generated.resources.ic_volume_on
import immichgallery.composeapp.generated.resources.video_duration_unknown
import immichgallery.composeapp.generated.resources.video_mute
import immichgallery.composeapp.generated.resources.video_pause
import immichgallery.composeapp.generated.resources.video_play
import immichgallery.composeapp.generated.resources.video_position
import immichgallery.composeapp.generated.resources.video_time
import immichgallery.composeapp.generated.resources.video_unmute
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val SLIDER_INACTIVE_TRACK_ALPHA = 0.4f

/**
 * Ephemeral per-player UI state for normal video. [userPaused] is the user's
 * intent and is never written by lifecycle/offscreen pauses.
 */
@Stable
internal class NormalVideoPlaybackState {
    var userPaused by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var positionSeconds by mutableFloatStateOf(0f)
    var durationSeconds by mutableFloatStateOf(0f)
}

/** PauseChange is ignored: it only echoes app commands, including lifecycle pauses. */
internal fun NormalVideoPlaybackState.applyChaintechEvent(event: MediaPlayerEvent) {
    when (event) {
        is MediaPlayerEvent.CurrentTimeChange -> positionSeconds = event.currentTime
        is MediaPlayerEvent.TotalTimeChange -> durationSeconds = event.totalTime
        is MediaPlayerEvent.MuteChange -> muted = event.isMuted
        else -> Unit
    }
}

internal fun validVideoDuration(seconds: Float): Float? = seconds.takeIf { it.isFinite() && it > 0f }

internal fun formatVideoTime(seconds: Float): String {
    val total = seconds.takeIf { it.isFinite() && it > 0f }?.toLong() ?: 0L
    val hours = total / 3_600
    val minutes = (total % 3_600) / 60
    val secs = (total % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$secs" else "$minutes:$secs"
}

@Composable
internal fun NormalVideoControls(
    state: NormalVideoPlaybackState,
    visible: Boolean,
    onPlayPause: () -> Unit,
    onMuteToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onInteraction: () -> Unit,
    onHoldChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val latestOnHoldChange by rememberUpdatedState(onHoldChange)
    // Hiding, paging away or disposal must never leave the chrome pinned.
    DisposableEffect(Unit) { onDispose { latestOnHoldChange(false) } }
    var preview by remember { mutableStateOf<Float?>(null) }
    val duration = validVideoDuration(state.durationSeconds)
    val rawPosition = (preview ?: state.positionSeconds).takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val position = duration?.let { rawPosition.coerceAtMost(it) } ?: rawPosition
    val seekDescription = stringResource(Res.string.video_position)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = DETAIL_BAR_ALPHA))
            .pointerInput(Unit) { observePointerHolds { held -> latestOnHoldChange(held) } }
            .padding(horizontal = Dimens.smallSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { onPlayPause(); onInteraction() }) {
            Icon(
                painterResource(if (state.userPaused) Res.drawable.ic_play else Res.drawable.ic_pause),
                contentDescription = stringResource(if (state.userPaused) Res.string.video_play else Res.string.video_pause),
                tint = Color.White
            )
        }
        Slider(
            value = if (duration != null) position else 0f,
            onValueChange = { preview = it },
            onValueChangeFinished = {
                val target = preview
                preview = null
                if (target != null && duration != null) onSeek(target.coerceIn(0f, duration))
                onInteraction()
            },
            valueRange = 0f..(duration ?: 1f),
            enabled = duration != null,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = SLIDER_INACTIVE_TRACK_ALPHA)
            ),
            modifier = Modifier.weight(1f).semantics { contentDescription = seekDescription }
        )
        Text(
            text = stringResource(
                Res.string.video_time,
                formatVideoTime(position),
                duration?.let(::formatVideoTime) ?: stringResource(Res.string.video_duration_unknown)
            ),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Dimens.smallSpacing)
        )
        IconButton(onClick = { onMuteToggle(); onInteraction() }) {
            Icon(
                painterResource(if (state.muted) Res.drawable.ic_volume_off else Res.drawable.ic_volume_on),
                contentDescription = stringResource(if (state.muted) Res.string.video_unmute else Res.string.video_mute),
                tint = Color.White
            )
        }
    }
}
