package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable

@Composable
expect fun rememberHapticFeedback(): HapticFeedbackHandler

expect class HapticFeedbackHandler {
    fun performTick()
}
