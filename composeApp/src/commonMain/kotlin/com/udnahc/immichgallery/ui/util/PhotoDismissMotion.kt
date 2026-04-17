package com.udnahc.immichgallery.ui.util

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.dp

object PhotoDismissMotion {
    val dismissThreshold = 150.dp
    val flickVelocity = 1200.dp
    const val MIN_SCALE = 0.5f
    const val EXIT_DURATION_MS = 300

    val exitSpec: AnimationSpec<Float> = tween(
        durationMillis = EXIT_DURATION_MS,
        easing = FastOutSlowInEasing,
    )

    val snapBackSpec: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
}
