package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit
)
