package com.udnahc.immichgallery.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun PlatformVideoPlayer(
    url: String,
    apiKey: String,
    isCurrentPage: Boolean,
    modifier: Modifier = Modifier
)
