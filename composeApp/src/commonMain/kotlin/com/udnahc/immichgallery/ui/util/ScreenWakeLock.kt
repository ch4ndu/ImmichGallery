package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable

@Composable
expect fun rememberScreenWakeLock(): ScreenWakeLock

interface ScreenWakeLock {
    fun acquire()
    fun release()
}
