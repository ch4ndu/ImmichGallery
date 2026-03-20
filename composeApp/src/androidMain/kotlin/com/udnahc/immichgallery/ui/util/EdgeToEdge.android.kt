package com.udnahc.immichgallery.ui.util

import android.app.Activity
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

private var activityRef: java.lang.ref.WeakReference<ComponentActivity>? = null

fun registerActivity(activity: ComponentActivity) {
    activityRef = java.lang.ref.WeakReference(activity)
}

actual fun restoreEdgeToEdge() {
    activityRef?.get()?.enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ),
        navigationBarStyle = SystemBarStyle.auto(
            android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
        ),
    )
}
