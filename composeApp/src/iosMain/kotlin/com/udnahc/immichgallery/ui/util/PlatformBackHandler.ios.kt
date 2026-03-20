package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op: iOS uses its own swipe gesture for navigation
}
