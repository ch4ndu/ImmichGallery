package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class SlideshowConfig(
    val order: SlideshowOrder = SlideshowOrder.SEQUENTIAL,
    val durationSeconds: Int = 5,
    val animations: SlideshowAnimations = SlideshowAnimations.RANDOM
)

enum class SlideshowOrder { SEQUENTIAL, RANDOM }
enum class SlideshowAnimations { OFF, RANDOM }
