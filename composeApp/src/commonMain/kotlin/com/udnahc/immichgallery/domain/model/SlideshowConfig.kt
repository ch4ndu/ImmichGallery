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
