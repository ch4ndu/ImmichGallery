package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIApplication

@Composable
actual fun rememberScreenWakeLock(): ScreenWakeLock {
    return remember { IosScreenWakeLock() }
}

private class IosScreenWakeLock : ScreenWakeLock {
    override fun acquire() {
        UIApplication.sharedApplication.idleTimerDisabled = true
    }

    override fun release() {
        UIApplication.sharedApplication.idleTimerDisabled = false
    }
}
