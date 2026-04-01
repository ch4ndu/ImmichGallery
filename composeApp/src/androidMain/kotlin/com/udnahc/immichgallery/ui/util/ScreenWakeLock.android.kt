package com.udnahc.immichgallery.ui.util

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberScreenWakeLock(): ScreenWakeLock {
    return remember { AndroidScreenWakeLock() }
}

private class AndroidScreenWakeLock : ScreenWakeLock {
    override fun acquire() {
        activityRef?.get()?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun release() {
        activityRef?.get()?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
