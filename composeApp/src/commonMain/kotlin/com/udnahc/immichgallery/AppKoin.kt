package com.udnahc.immichgallery

import androidx.compose.runtime.Composable

@Composable
expect fun AppKoin(content: @Composable () -> Unit)
