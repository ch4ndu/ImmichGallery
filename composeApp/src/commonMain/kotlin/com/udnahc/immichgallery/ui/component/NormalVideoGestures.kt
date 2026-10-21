package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.udnahc.immichgallery.ui.theme.Dimens
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.video_brightness
import immichgallery.composeapp.generated.resources.video_level_percent
import immichgallery.composeapp.generated.resources.video_volume
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

private const val SIDE_GESTURE_EDGE_FRACTION = 0.3f
private const val HUD_TRACK_ALPHA = 0.4f

internal enum class NormalVideoAdjustment { Brightness, Volume }

/**
 * Normal-video surface that turns vertical drags in the left/right outer bands into
 * brightness/volume adjustments with a transient HUD. Levels are 0..1; one full
 * surface-height drag spans the range. Claiming holds the shared chrome under
 * [ViewerChromeHold.VideoGesture]; release, cancellation and disposal always end it.
 */
@Composable
internal fun NormalVideoGestureSurface(
    enabled: Boolean,
    brightnessLevel: () -> Float,
    volumeLevel: () -> Float,
    onBrightnessChange: (Float) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onHoldChange: (ViewerChromeHold, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var adjusting by remember { mutableStateOf<NormalVideoAdjustment?>(null) }
    var level by remember { mutableFloatStateOf(0f) }
    val latestBrightnessLevel by rememberUpdatedState(brightnessLevel)
    val latestVolumeLevel by rememberUpdatedState(volumeLevel)
    val latestOnBrightnessChange by rememberUpdatedState(onBrightnessChange)
    val latestOnVolumeChange by rememberUpdatedState(onVolumeChange)
    val latestOnHoldChange by rememberUpdatedState(onHoldChange)

    Box(
        modifier = modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectNormalVideoSideDrags(
                onStart = { adjustment ->
                    level = when (adjustment) {
                        NormalVideoAdjustment.Brightness -> latestBrightnessLevel()
                        NormalVideoAdjustment.Volume -> latestVolumeLevel()
                    }.coerceIn(0f, 1f)
                    adjusting = adjustment
                    latestOnHoldChange(ViewerChromeHold.VideoGesture, true)
                },
                onDrag = { adjustment, fraction ->
                    level = (level + fraction).coerceIn(0f, 1f)
                    when (adjustment) {
                        NormalVideoAdjustment.Brightness -> latestOnBrightnessChange(level)
                        NormalVideoAdjustment.Volume -> latestOnVolumeChange(level)
                    }
                },
                onEnd = {
                    adjusting = null
                    latestOnHoldChange(ViewerChromeHold.VideoGesture, false)
                },
            )
        }
    ) {
        content()
        adjusting?.let { NormalVideoAdjustmentHud(it, level, Modifier.align(Alignment.Center)) }
    }
}

internal fun normalVideoAdjustmentAt(x: Float, width: Int): NormalVideoAdjustment? = when {
    width <= 0 -> null
    x < width * SIDE_GESTURE_EDGE_FRACTION -> NormalVideoAdjustment.Brightness
    x > width * (1f - SIDE_GESTURE_EDGE_FRACTION) -> NormalVideoAdjustment.Volume
    else -> null
}

/**
 * Claims a single-pointer (or primary-button) drag only after vertical slop in a side band.
 * Horizontal-dominant motion is left to the pager and central drags to dismiss/Info. The
 * slop-crossing change and every later claimed move are consumed on the Main pass, which
 * reaches this surface before the pager and parent dismiss detector, so they see no movement.
 * [onDrag] receives upward movement as a fraction of the surface height.
 */
private suspend fun PointerInputScope.detectNormalVideoSideDrags(
    onStart: (NormalVideoAdjustment) -> Unit,
    onDrag: (NormalVideoAdjustment, Float) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
        val adjustment = normalVideoAdjustmentAt(down.position.x, size.width) ?: return@awaitEachGesture
        val touchSlop = viewConfiguration.touchSlop
        var total = Offset.Zero
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            total += change.positionChange()
            if (abs(total.x) > touchSlop && abs(total.x) > abs(total.y)) return@awaitEachGesture
            if (abs(total.y) > touchSlop) {
                change.consume()
                break
            }
        }
        val height = size.height.toFloat().takeIf { it > 0f } ?: return@awaitEachGesture
        onStart(adjustment)
        try {
            // The claimed slop travel counts toward the adjustment so a full-height drag spans 0..1.
            onDrag(adjustment, -total.y / height)
            while (true) {
                val event = awaitPointerEvent()
                // A second finger ends the adjustment at its current level.
                if (event.changes.count { it.pressed } > 1) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val deltaY = change.positionChange().y
                change.consume()
                if (deltaY != 0f) onDrag(adjustment, -deltaY / height)
            }
        } finally {
            onEnd()
        }
    }
}

@Composable
private fun NormalVideoAdjustmentHud(adjustment: NormalVideoAdjustment, level: Float, modifier: Modifier) {
    val label = stringResource(
        when (adjustment) {
            NormalVideoAdjustment.Brightness -> Res.string.video_brightness
            NormalVideoAdjustment.Volume -> Res.string.video_volume
        }
    )
    val percent = stringResource(Res.string.video_level_percent, (level * 100).roundToInt())
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = DETAIL_BAR_ALPHA), MaterialTheme.shapes.medium)
            .padding(Dimens.largeSpacing)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = percent
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        LinearProgressIndicator(
            progress = { level },
            color = Color.White,
            trackColor = Color.White.copy(alpha = HUD_TRACK_ALPHA),
            modifier = Modifier.padding(vertical = Dimens.mediumSpacing)
        )
        Text(percent, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}
