package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class SlideshowConfig(
    val order: SlideshowOrder = SlideshowOrder.SEQUENTIAL,
    val durationSeconds: Int = 5,
    val motion: SlideshowMotion = SlideshowMotion.PAN_AND_ZOOM,
    val transition: SlideshowTransition = SlideshowTransition.SLIDE,
    val videoPolicy: SlideshowVideoPolicy = SlideshowVideoPolicy.THUMBNAIL,
    val muted: Boolean = true,
    val videoLimitSeconds: Int? = null,
    val captions: Boolean = false,
    val fullscreen: Boolean = false
) {
    val isValid: Boolean
        get() = durationSeconds in SLIDESHOW_DURATION_SECONDS &&
                (videoLimitSeconds == null || videoLimitSeconds > 0)

    val normalized: SlideshowConfig
        get() = copy(
            durationSeconds = durationSeconds.coerceIn(SLIDESHOW_DURATION_SECONDS),
            videoLimitSeconds = videoLimitSeconds?.takeIf { it > 0 }
        )
}

val SLIDESHOW_DURATION_SECONDS = 1..120
enum class SlideshowOrder { SEQUENTIAL, RANDOM }
enum class SlideshowMotion { STILL, PAN_AND_ZOOM }
enum class SlideshowTransition { CUT, FADE, SLIDE }
enum class SlideshowVideoPolicy { SKIP, THUMBNAIL, PLAY }
