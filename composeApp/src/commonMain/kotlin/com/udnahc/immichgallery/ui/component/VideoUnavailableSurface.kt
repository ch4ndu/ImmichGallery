package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.observeNonConsumingTap

/** Recovery actions sit above a sibling tap backdrop so Retry cannot toggle viewer chrome. */
@Composable
internal fun VideoUnavailableSurface(
    normalState: NormalVideoPlaybackState?,
    isCurrentPage: Boolean,
    onTap: (() -> Unit)?,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (onTap != null) Box(Modifier.matchParentSize().observeNonConsumingTap(onTap))
        Column(
            modifier = Modifier.padding(Dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing),
        ) {
            if (message != null) Text(message, color = Color.White, textAlign = TextAlign.Center)
            normalState?.let { state -> NormalVideoRecovery(state, isCurrentPage, onInteraction) }
        }
    }
}
