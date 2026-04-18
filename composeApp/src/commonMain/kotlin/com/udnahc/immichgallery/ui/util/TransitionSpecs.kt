package com.udnahc.immichgallery.ui.util

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

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
const val PHOTO_TRANSITION_DURATION_MS = 300

val photoTransitionFadeIn: EnterTransition = fadeIn(tween(PHOTO_TRANSITION_DURATION_MS))
val photoTransitionFadeOut: ExitTransition = fadeOut(tween(PHOTO_TRANSITION_DURATION_MS))

@OptIn(ExperimentalSharedTransitionApi::class)
val photoTransitionBoundsTransform: BoundsTransform = BoundsTransform { _, _ ->
    tween(durationMillis = PHOTO_TRANSITION_DURATION_MS)
}
