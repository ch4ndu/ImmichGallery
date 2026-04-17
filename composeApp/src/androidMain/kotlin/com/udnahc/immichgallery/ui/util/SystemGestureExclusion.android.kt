package com.udnahc.immichgallery.ui.util

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

actual fun Modifier.excludeFromSystemGestures(): Modifier = this.systemGestureExclusion()
