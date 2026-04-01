package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberScreenWakeLock(): ScreenWakeLock {
    return remember { JvmScreenWakeLock() }
}

private class JvmScreenWakeLock : ScreenWakeLock {
    override fun acquire() { /* No-op on desktop */ }
    override fun release() { /* No-op on desktop */ }
}
