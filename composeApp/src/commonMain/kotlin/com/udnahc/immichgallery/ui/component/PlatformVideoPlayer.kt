package com.udnahc.immichgallery.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.udnahc.immichgallery.domain.model.SlideshowMediaRequest

@Composable
expect fun PlatformVideoPlayer(
    playbackUrl: String,
    originalUrl: String,
    apiKey: String,
    isCurrentPage: Boolean,
    onTap: () -> Unit = {},
    modifier: Modifier = Modifier,
    slideshowRequest: SlideshowMediaRequest? = null,
    muted: Boolean = false,
    onSlideshowEvent: (SlideshowMediaRequest, SlideshowMediaEvent) -> Unit = { _, _ -> }
)
