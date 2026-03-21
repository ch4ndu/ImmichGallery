package com.udnahc.immichgallery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationWillResignActiveNotification

actual object AppLifecycle {
    actual val isForeground: StateFlow<Boolean> = MutableStateFlow(true).also { state ->
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            UIApplicationWillResignActiveNotification,
            null,
            NSOperationQueue.mainQueue
        ) { _ ->
            state.value = false
        }
        center.addObserverForName(
            UIApplicationDidBecomeActiveNotification,
            null,
            NSOperationQueue.mainQueue
        ) { _ ->
            state.value = true
        }
    }
}
