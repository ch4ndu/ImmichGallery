package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import chaintech.videoplayer.host.MediaPlayerEvent
import chaintech.videoplayer.model.PlayerSpeed
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.ic_pause
import immichgallery.composeapp.generated.resources.ic_play
import immichgallery.composeapp.generated.resources.ic_skip_backward
import immichgallery.composeapp.generated.resources.ic_skip_forward
import immichgallery.composeapp.generated.resources.ic_volume_off
import immichgallery.composeapp.generated.resources.ic_volume_on
import immichgallery.composeapp.generated.resources.video_duration_unknown
import immichgallery.composeapp.generated.resources.video_mute
import immichgallery.composeapp.generated.resources.video_pause
import immichgallery.composeapp.generated.resources.video_play
import immichgallery.composeapp.generated.resources.video_position
import immichgallery.composeapp.generated.resources.video_skip_backward
import immichgallery.composeapp.generated.resources.video_skip_forward
import immichgallery.composeapp.generated.resources.video_speed
import immichgallery.composeapp.generated.resources.video_speed_0_5
import immichgallery.composeapp.generated.resources.video_speed_1
import immichgallery.composeapp.generated.resources.video_speed_1_5
import immichgallery.composeapp.generated.resources.video_speed_2
import immichgallery.composeapp.generated.resources.video_time
import immichgallery.composeapp.generated.resources.video_unmute
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

private const val SLIDER_INACTIVE_TRACK_ALPHA = 0.4f
private const val DISABLED_CONTROL_ALPHA = 0.4f
private const val VIDEO_SKIP_SECONDS = 10f

internal enum class NormalVideoSpeed(val rate: Float, val playerSpeed: PlayerSpeed, val label: StringResource) {
    Half(0.5f, PlayerSpeed.X0_5, Res.string.video_speed_0_5),
    Normal(1f, PlayerSpeed.X1, Res.string.video_speed_1),
    OneAndHalf(1.5f, PlayerSpeed.X1_5, Res.string.video_speed_1_5),
    Twice(2f, PlayerSpeed.X2, Res.string.video_speed_2),
}

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
    var speed by mutableStateOf(NormalVideoSpeed.Normal)

    /** Player (not system) volume the user chose; mute keeps it so unmuting restores this level. */
    var volume by mutableFloatStateOf(1f)

    /** Null until the first brightness gesture, so entering a video follows the screen's current level. */
    var brightness by mutableStateOf<Float?>(null)
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

internal val NormalVideoPlaybackState.effectiveVolume: Float get() = if (muted) 0f else volume

/**
 * Raising from mute unmutes before applying the level; dragging a muted player toward zero
 * changes nothing, so mute/unmute keeps restoring the chosen level.
 */
internal fun NormalVideoPlaybackState.adjustVolume(level: Float, unmute: () -> Unit, setVolume: (Float) -> Unit) {
    if (muted) {
        if (level <= 0f) return
        unmute()
    }
    volume = level
    setVolume(level)
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
    onHoldChange: (ViewerChromeHold, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val latestOnHoldChange by rememberUpdatedState(onHoldChange)
    // Hiding, paging away or disposal must never leave the chrome pinned.
    DisposableEffect(Unit) { onDispose { latestOnHoldChange(ViewerChromeHold.VideoControls, false) } }
    var preview by remember { mutableStateOf<Float?>(null) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    // The open speed menu holds chrome under its own owner; closing it, or losing visibility
    // (which disposes these controls), releases only that hold.
    DisposableEffect(speedMenuExpanded) {
        val expanded = speedMenuExpanded
        if (expanded) latestOnHoldChange(ViewerChromeHold.VideoSpeedMenu, true)
        onDispose { if (expanded) latestOnHoldChange(ViewerChromeHold.VideoSpeedMenu, false) }
    }
    val duration = validVideoDuration(state.durationSeconds)
    val rawPosition = (preview ?: state.positionSeconds).takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
    val position = duration?.let { rawPosition.coerceAtMost(it) } ?: rawPosition
    val seekDescription = stringResource(Res.string.video_position)
    val speedDescription = stringResource(Res.string.video_speed)
    val seek: (Float) -> Unit = { target ->
        state.positionSeconds = target
        onSeek(target)
    }
    val skip: (Float) -> Unit = { delta ->
        val current = state.positionSeconds.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        validVideoDuration(state.durationSeconds)?.let { total -> seek((current + delta).coerceIn(0f, total)) }
        onInteraction()
    }
    val skipTint = if (duration != null) Color.White else Color.White.copy(alpha = DISABLED_CONTROL_ALPHA)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = DETAIL_BAR_ALPHA))
            .pointerInput(Unit) {
                observePointerHolds { held -> latestOnHoldChange(ViewerChromeHold.VideoControls, held) }
            }
            .padding(horizontal = Dimens.smallSpacing)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = if (duration != null) position else 0f,
                onValueChange = { preview = it },
                onValueChangeFinished = {
                    val target = preview
                    preview = null
                    if (target != null && duration != null) seek(target.coerceIn(0f, duration))
                    onInteraction()
                },
                valueRange = 0f..(duration ?: 1f),
                enabled = duration != null,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = SLIDER_INACTIVE_TRACK_ALPHA)
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimens.smallSpacing)
                    .semantics { contentDescription = seekDescription }
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
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onPlayPause(); onInteraction() }) {
                Icon(
                    painterResource(if (state.userPaused) Res.drawable.ic_play else Res.drawable.ic_pause),
                    contentDescription = stringResource(if (state.userPaused) Res.string.video_play else Res.string.video_pause),
                    tint = Color.White
                )
            }
            IconButton(onClick = { skip(-VIDEO_SKIP_SECONDS) }, enabled = duration != null) {
                Icon(
                    painterResource(Res.drawable.ic_skip_backward),
                    contentDescription = stringResource(Res.string.video_skip_backward),
                    tint = skipTint
                )
            }
            IconButton(onClick = { skip(VIDEO_SKIP_SECONDS) }, enabled = duration != null) {
                Icon(
                    painterResource(Res.drawable.ic_skip_forward),
                    contentDescription = stringResource(Res.string.video_skip_forward),
                    tint = skipTint
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(
                    onClick = { speedMenuExpanded = true; onInteraction() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    modifier = Modifier.semantics { contentDescription = speedDescription }
                ) {
                    Text(stringResource(state.speed.label), maxLines = 1)
                }
                DropdownMenu(
                    expanded = speedMenuExpanded,
                    onDismissRequest = { speedMenuExpanded = false; onInteraction() }
                ) {
                    NormalVideoSpeed.entries.forEach { option ->
                        val selected = option == state.speed
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(option.label),
                                    fontWeight = if (selected) FontWeight.Bold else null
                                )
                            },
                            // Selection only changes the rate; it never resumes a paused video.
                            onClick = {
                                state.speed = option
                                speedMenuExpanded = false
                                onInteraction()
                            },
                            modifier = Modifier.semantics { this.selected = selected }
                        )
                    }
                }
            }
            IconButton(onClick = { onMuteToggle(); onInteraction() }) {
                Icon(
                    painterResource(if (state.muted) Res.drawable.ic_volume_off else Res.drawable.ic_volume_on),
                    contentDescription = stringResource(if (state.muted) Res.string.video_unmute else Res.string.video_mute),
                    tint = Color.White
                )
            }
        }
    }
}
