package com.udnahc.immichgallery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object AppLifecycle {
    private val foregroundState = MutableStateFlow(true)
    actual val isForeground: StateFlow<Boolean> = foregroundState

    internal fun setForeground(isForeground: Boolean) {
        foregroundState.value = isForeground
    }
}
