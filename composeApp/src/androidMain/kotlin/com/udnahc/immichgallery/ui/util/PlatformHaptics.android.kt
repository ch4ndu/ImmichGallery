package com.udnahc.immichgallery.ui.util

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
actual fun rememberHapticFeedback(): HapticFeedbackHandler {
    val view = LocalView.current
    return remember { HapticFeedbackHandler(view) }
}

actual class HapticFeedbackHandler(private val view: View) {
    actual fun performTick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}
