package com.udnahc.immichgallery.ui.util

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * The sharedBounds cross-fade enter/exit for the grid-side and detail-side
 * thumbnails. With both sides sized to the asset's aspect ratio and rendering the
 * same Fit image, the cross-fade has nothing visually different to blend between;
 * disabling the alpha transitions here prevents the grid thumbnail from fading
 * out early when bounds and alpha drivers drift apart over long durations.
 */
val photoSharedBoundsEnter: EnterTransition = EnterTransition.None
val photoSharedBoundsExit: ExitTransition = ExitTransition.None

/**
 * Shared timing for the grid → photo-detail shared-element transition.
 *
 * The grid-side and detail-side `sharedBounds` sites, plus the `AnimatedVisibility`
 * wrappers that host them, all must use the SAME animation spec — otherwise the
 * alpha fade (AnimatedVisibility) and the bounds animation (sharedBounds) settle
 * at different times and the user sees a two-phase transition / end-of-animation
 * flash. A single `tween` ties everything to the same duration.
 */
const val PHOTO_TRANSITION_DURATION_MS = 500

val photoTransitionFadeIn: EnterTransition = fadeIn(tween(PHOTO_TRANSITION_DURATION_MS))
val photoTransitionFadeOut: ExitTransition = fadeOut(tween(PHOTO_TRANSITION_DURATION_MS))

/**
 * Dynamic switch that tells [rememberPhotoBoundsTransform] whether the current
 * shared-element animation is an open / dismiss (tween 500 ms) or a pager-driven
 * mid-overlay transition (snap, 0 ms). True = tween, false = snap. Screens flip
 * this true for the duration of the open/close animation via a LaunchedEffect
 * keyed on `selectedAssetId`.
 */
val LocalPhotoBoundsTween = compositionLocalOf { false }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun rememberPhotoBoundsTransform(): BoundsTransform {
    val useTween = LocalPhotoBoundsTween.current
    return remember(useTween) {
        BoundsTransform { _, _ ->
            if (useTween) {
                tween(durationMillis = PHOTO_TRANSITION_DURATION_MS)
            } else {
                snap()
            }
        }
    }
}

/**
 * Fade specs for surrounding system-UI-like bars (MainScreen top/bottom, screen
 * DetailTopBars). Bars should stay visible during the shared-element flight so
 * the photo transitions against a stable frame, then hide after it completes.
 * Exit is delayed by the full shared-element duration; enter is quick so bars
 * reappear promptly when the overlay is dismissed.
 */
private const val BAR_FADE_IN_MS = 200
private const val BAR_FADE_OUT_MS = 150

val systemBarFadeIn: EnterTransition = fadeIn(tween(BAR_FADE_IN_MS))
val systemBarFadeOut: ExitTransition = fadeOut(
    tween(durationMillis = BAR_FADE_OUT_MS, delayMillis = PHOTO_TRANSITION_DURATION_MS)
)
