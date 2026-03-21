package com.udnahc.immichgallery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object AppLifecycle {
    actual val isForeground: StateFlow<Boolean> = MutableStateFlow(true)
}
