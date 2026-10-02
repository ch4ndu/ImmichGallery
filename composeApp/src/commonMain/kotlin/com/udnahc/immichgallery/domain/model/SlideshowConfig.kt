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

    // Compatibility for existing overlay callers until they adopt independent motion/transition.
    constructor(order: SlideshowOrder, durationSeconds: Int, animations: SlideshowAnimations) : this(
        order = order,
        durationSeconds = durationSeconds,
        motion = if (animations == SlideshowAnimations.OFF) SlideshowMotion.STILL
        else SlideshowMotion.PAN_AND_ZOOM
    )

    val animations: SlideshowAnimations
        get() = if (motion == SlideshowMotion.STILL) SlideshowAnimations.OFF
        else SlideshowAnimations.RANDOM
}

val SLIDESHOW_DURATION_SECONDS = 1..120
enum class SlideshowOrder { SEQUENTIAL, RANDOM }
enum class SlideshowAnimations { OFF, RANDOM }
enum class SlideshowMotion { STILL, PAN_AND_ZOOM }
enum class SlideshowTransition { CUT, FADE, SLIDE }
enum class SlideshowVideoPolicy { SKIP, THUMBNAIL, PLAY }

/**
 * Picks the next page when slideshow is active. SEQUENTIAL wraps forward/backward
 * by one; RANDOM picks a fresh page that isn't the current one. Manual arrow-key
 * advances share this logic so left/right during a slideshow follow the chosen
 * order (and the timer caller resets on settle).
 */
fun nextSlideshowPage(
    order: SlideshowOrder,
    current: Int,
    total: Int,
    forward: Boolean,
): Int {
    if (total <= 1) return current
    return when (order) {
        SlideshowOrder.RANDOM -> {
            var r = (0 until total).random()
            while (r == current) r = (0 until total).random()
            r
        }

        SlideshowOrder.SEQUENTIAL -> {
            if (forward) (current + 1) % total else (current - 1 + total) % total
        }
    }
}
