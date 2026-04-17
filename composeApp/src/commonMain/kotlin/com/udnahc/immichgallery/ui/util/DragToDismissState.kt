package com.udnahc.immichgallery.ui.util

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class DismissOutcome { DISMISS, SNAP_BACK }

private val OffsetVectorConverter = TwoWayConverter<Offset, AnimationVector2D>(
    convertToVector = { AnimationVector2D(it.x, it.y) },
    convertFromVector = { Offset(it.v1, it.v2) },
)

/**
 * Animation state driving the Google-Photos-style drag-to-dismiss gesture.
 *
 * Callers feed raw pointer deltas via [onDrag]; [translation], [scale], and
 * [scrimAlpha] are read by composables — typically a `graphicsLayer` on a
 * parent that wraps both the sharedBounds thumbnail and the zoomable image,
 * plus a scrim Box behind the page content.
 *
 * On release, [onRelease] either runs the exit animation in parallel with the
 * caller's `popBackStack` (which drives the existing sharedBounds fly-back),
 * or springs back to identity if the release was below the dismiss threshold.
 */
@Stable
class DragToDismissState(
    private val scope: CoroutineScope,
    private val dismissThresholdPx: Float,
    private val flickVelocityPx: Float,
    private val exitSpec: AnimationSpec<Float>,
    private val snapBackSpec: AnimationSpec<Float>,
) {
    private val translationAnim = Animatable(Offset.Zero, OffsetVectorConverter)
    private val scaleAnim = Animatable(1f)
    private val scrimAlphaAnim = Animatable(1f)

    /** Latched once [onDrag] sees dy past [dismissThresholdPx]; resets on cancel. */
    private var committed by mutableStateOf(false)

    /**
     * Pivot for scaling the page content, normalized to the page bounds.
     * Defaults to center; set by [onDragStart] to the finger's down position
     * so that the image visually "hangs" under the finger as it scales down.
     */
    var pivot: TransformOrigin by mutableStateOf(TransformOrigin.Center)
        private set

    val translation: Offset get() = translationAnim.value
    val scale: Float get() = scaleAnim.value

    /** 1f = fully opaque black, 0f = fully transparent. */
    val scrimAlpha: Float get() = scrimAlphaAnim.value

    private val isActiveState = derivedStateOf {
        translationAnim.value != Offset.Zero || scaleAnim.value != 1f
    }
    val isActive: Boolean get() = isActiveState.value

    /** Called on touch-down, before any movement. */
    fun onDragStart(downPosition: Offset, containerSize: IntSize) {
        pivot = if (containerSize.width > 0 && containerSize.height > 0) {
            TransformOrigin(
                downPosition.x / containerSize.width.toFloat(),
                downPosition.y / containerSize.height.toFloat(),
            )
        } else TransformOrigin.Center
        committed = false
        // Scrim fades to fully transparent as soon as drag begins.
        scope.launch { scrimAlphaAnim.snapTo(0f) }
    }

    /**
     * Feed cumulative drag offset from the start of the gesture.
     *
     * Pre-commit: only positive Y drives scale (ensures the gesture is
     * reversible until the threshold is crossed). Past the threshold we latch
     * into committed mode — scale is pegged at the minimum and translation
     * follows the finger in any direction, so the user can drag the shrunken
     * image anywhere on the screen before releasing to dismiss.
     */
    fun onDrag(totalOffset: Offset) {
        if (!committed && totalOffset.y > dismissThresholdPx) {
            committed = true
        }

        if (committed) {
            scope.launch { translationAnim.snapTo(totalOffset) }
            scope.launch { scaleAnim.snapTo(PhotoDismissMotion.MIN_SCALE) }
            return
        }

        val dy = totalOffset.y.coerceAtLeast(0f)
        if (dy == 0f) {
            scope.launch { translationAnim.snapTo(Offset.Zero) }
            scope.launch { scaleAnim.snapTo(1f) }
            return
        }
        val progress = (dy / dismissThresholdPx).coerceIn(0f, 1f)
        val targetScale = 1f + (PhotoDismissMotion.MIN_SCALE - 1f) * progress
        scope.launch { translationAnim.snapTo(Offset(totalOffset.x, dy)) }
        scope.launch { scaleAnim.snapTo(targetScale) }
    }

    /**
     * Decide dismiss vs snap back. Dismiss once threshold has been latched OR
     * on a fast downward flick (even if threshold wasn't reached).
     */
    fun onRelease(velocityY: Float): DismissOutcome {
        val dismiss = committed || velocityY > flickVelocityPx
        if (dismiss) {
            scope.launch { translationAnim.animateTo(Offset.Zero, offsetSpec(exitSpec)) }
            scope.launch { scaleAnim.animateTo(1f, exitSpec) }
            scope.launch { scrimAlphaAnim.animateTo(0f, exitSpec) }
        } else {
            scope.launch { translationAnim.animateTo(Offset.Zero, offsetSpec(snapBackSpec)) }
            scope.launch { scaleAnim.animateTo(1f, snapBackSpec) }
            scope.launch { scrimAlphaAnim.animateTo(1f, snapBackSpec) }
        }
        return if (dismiss) DismissOutcome.DISMISS else DismissOutcome.SNAP_BACK
    }

    /** Snap back to identity (e.g. multi-touch cancellation). */
    fun cancel() {
        committed = false
        scope.launch { translationAnim.animateTo(Offset.Zero, offsetSpec(snapBackSpec)) }
        scope.launch { scaleAnim.animateTo(1f, snapBackSpec) }
        scope.launch { scrimAlphaAnim.animateTo(1f, snapBackSpec) }
    }
}

/**
 * Lifts a Float-valued AnimationSpec to Offset. AnimationSpec<T> is generic
 * over the target type via its VectorizedAnimationSpec machinery, so this
 * unchecked cast is safe for duration/spring specs that don't bind T.
 */
@Suppress("UNCHECKED_CAST")
private fun offsetSpec(source: AnimationSpec<Float>): AnimationSpec<Offset> =
    source as AnimationSpec<Offset>
