package com.udnahc.immichgallery

import kotlinx.coroutines.flow.StateFlow

expect object AppLifecycle {
    val isForeground: StateFlow<Boolean>
}
