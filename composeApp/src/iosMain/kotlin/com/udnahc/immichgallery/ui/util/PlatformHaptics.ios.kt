package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle

@Composable
actual fun rememberHapticFeedback(): HapticFeedbackHandler {
    return remember { HapticFeedbackHandler() }
}

actual class HapticFeedbackHandler {
    private val generator = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)

    actual fun performTick() {
        generator.impactOccurred()
    }
}
