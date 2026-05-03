package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.udnahc.immichgallery.domain.model.RowHeightBounds

@Composable
expect fun Modifier.desktopDetailShortcuts(
    enabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    onToggleSlideshow: () -> Unit,
    onInfo: () -> Unit,
): Modifier

expect fun Modifier.desktopGridZoom(
    currentHeight: Float,
    bounds: RowHeightBounds,
    onHeightChanged: (Float) -> Unit,
): Modifier
