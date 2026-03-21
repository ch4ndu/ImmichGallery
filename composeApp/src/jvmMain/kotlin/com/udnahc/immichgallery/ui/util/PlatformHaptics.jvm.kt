package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberHapticFeedback(): HapticFeedbackHandler {
    return remember { HapticFeedbackHandler() }
}

actual class HapticFeedbackHandler {
    actual fun performTick() {
        // No haptic feedback on desktop
    }
}
