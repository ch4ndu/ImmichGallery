package com.udnahc.immichgallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamicColorScheme

private val SeedColor = Color(0xFF0CCE9C)

@Composable
fun ImmichGalleryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = dynamicColorScheme(
        seedColor = SeedColor,
        isDark = darkTheme,
        isAmoled = false
    )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
