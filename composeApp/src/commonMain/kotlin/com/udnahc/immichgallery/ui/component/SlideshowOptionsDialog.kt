package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.udnahc.immichgallery.domain.model.*
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.LocalViewerFullscreen
import immichgallery.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

internal data class SlideshowOptionsState(
    val config: SlideshowConfig,
    val durationText: String = config.durationSeconds.toString(),
    val limitText: String = config.videoLimitSeconds?.toString().orEmpty()
) {
    val validDuration: Boolean get() = durationText.toIntOrNull() in SLIDESHOW_DURATION_SECONDS
    val validLimit: Boolean get() = config.videoPolicy != SlideshowVideoPolicy.PLAY || limitText.isBlank() || limitText.toIntOrNull()?.let { it > 0 } == true
    val validated: SlideshowConfig? get() = if (validDuration && validLimit) config.copy(
        durationSeconds = durationText.toIntOrNull() ?: config.durationSeconds,
        videoLimitSeconds = if (config.videoPolicy == SlideshowVideoPolicy.PLAY) limitText.toIntOrNull() else config.videoLimitSeconds
    ) else null
}

@Composable
fun SlideshowOptionsDialog(
    onConfirm: (SlideshowConfig) -> Unit,
    onDismiss: () -> Unit,
    preferences: ViewerPreferencesViewModel = koinViewModel()
) {
    val saved by preferences.slideshowConfig.collectAsState()
    var state by remember { mutableStateOf(SlideshowOptionsState(saved)) }
    SlideshowOptionsContent(state, { state = it }, {
        state.validated?.let { config -> preferences.setSlideshowConfig(config); onConfirm(config) }
    }, onDismiss, supportsFullscreen = LocalViewerFullscreen.current.isSupported)
}

@Composable
internal fun SlideshowOptionsContent(
    state: SlideshowOptionsState,
    onChange: (SlideshowOptionsState) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    supportsFullscreen: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.detail_slideshow)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing)) {
                SlideshowChoice(Res.string.slideshow_order, SlideshowOrder.entries, state.config.order,
                    { if (it == SlideshowOrder.SEQUENTIAL) Res.string.slideshow_order_sequential else Res.string.slideshow_order_random }) {
                    onChange(state.copy(config = state.config.copy(order = it)))
                }
                OutlinedTextField(value = state.durationText, onValueChange = { onChange(state.copy(durationText = it)) },
                    label = { Text(stringResource(Res.string.slideshow_duration_seconds)) }, singleLine = true,
                    isError = !state.validDuration,
                    supportingText = { if (!state.validDuration) Text(stringResource(Res.string.slideshow_duration_invalid)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                SlideshowChoice(Res.string.slideshow_motion, SlideshowMotion.entries, state.config.motion,
                    { if (it == SlideshowMotion.STILL) Res.string.slideshow_still else Res.string.slideshow_pan_zoom }) {
                    onChange(state.copy(config = state.config.copy(motion = it)))
                }
                SlideshowChoice(Res.string.slideshow_transition, SlideshowTransition.entries, state.config.transition,
                    { when (it) { SlideshowTransition.CUT -> Res.string.slideshow_cut; SlideshowTransition.FADE -> Res.string.slideshow_fade; SlideshowTransition.SLIDE -> Res.string.slideshow_slide } }) {
                    onChange(state.copy(config = state.config.copy(transition = it)))
                }
                SlideshowChoice(Res.string.slideshow_videos, SlideshowVideoPolicy.entries, state.config.videoPolicy,
                    { when (it) { SlideshowVideoPolicy.SKIP -> Res.string.slideshow_skip; SlideshowVideoPolicy.THUMBNAIL -> Res.string.slideshow_thumbnail; SlideshowVideoPolicy.PLAY -> Res.string.slideshow_play_video } }) {
                    onChange(state.copy(config = state.config.copy(videoPolicy = it)))
                }
                if (state.config.videoPolicy == SlideshowVideoPolicy.PLAY) {
                    SlideshowToggle(Res.string.slideshow_muted, state.config.muted) { onChange(state.copy(config = state.config.copy(muted = it))) }
                    OutlinedTextField(value = state.limitText, onValueChange = { onChange(state.copy(limitText = it)) },
                        label = { Text(stringResource(Res.string.slideshow_video_limit)) }, singleLine = true,
                        isError = !state.validLimit,
                        supportingText = { Text(stringResource(if (state.validLimit) Res.string.slideshow_video_limit_hint else Res.string.slideshow_video_limit_invalid)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                if (supportsFullscreen) SlideshowToggle(Res.string.slideshow_fullscreen, state.config.fullscreen) {
                    onChange(state.copy(config = state.config.copy(fullscreen = it)))
                }
                SlideshowToggle(Res.string.slideshow_captions, state.config.captions) { onChange(state.copy(config = state.config.copy(captions = it))) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = state.validated != null) { Text(stringResource(Res.string.slideshow_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SlideshowChoice(title: StringResource, options: List<T>, selected: T, label: (T) -> StringResource, select: (T) -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.smallSpacing)) {
        options.forEach { value -> FilterChip(selected = value == selected, onClick = { select(value) }, label = { Text(stringResource(label(value))) }) }
    }
}

@Composable
private fun SlideshowToggle(label: StringResource, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange).padding(vertical = Dimens.smallSpacing),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(stringResource(label), Modifier.padding(start = Dimens.smallSpacing))
    }
}
