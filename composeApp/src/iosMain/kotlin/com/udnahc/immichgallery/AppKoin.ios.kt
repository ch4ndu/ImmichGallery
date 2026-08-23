package com.udnahc.immichgallery

import androidx.compose.runtime.Composable

@Composable
actual fun AppKoin(content: @Composable () -> Unit) {
    content()
}
