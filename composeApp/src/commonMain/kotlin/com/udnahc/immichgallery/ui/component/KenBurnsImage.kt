package com.udnahc.immichgallery.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlin.random.Random

private const val KEN_BURNS_DURATION_MS = 5000
private const val PAN_FRACTION = 0.04f

/**
 * Each preset defines start and end states for scale and pan direction.
 * Pan values are fractions of the container size, applied based on progress.
 */
private data class KenBurnsPreset(
    val startScale: Float,
    val endScale: Float,
    val startPanX: Float, // fraction of width
    val startPanY: Float, // fraction of height
    val endPanX: Float,
    val endPanY: Float
)

private val presets = listOf(
    // Zoom in, drift top-left → center
    KenBurnsPreset(1.0f, 1.25f, -PAN_FRACTION, -PAN_FRACTION, 0f, 0f),
    // Zoom in, drift bottom-right → center
    KenBurnsPreset(1.0f, 1.25f, PAN_FRACTION, PAN_FRACTION, 0f, 0f),
    // Zoom out, center → drift top-right
    KenBurnsPreset(1.25f, 1.0f, 0f, 0f, PAN_FRACTION, -PAN_FRACTION),
    // Zoom out, center → drift bottom-left
    KenBurnsPreset(1.25f, 1.0f, 0f, 0f, -PAN_FRACTION, PAN_FRACTION),
    // Slow zoom in from center
    KenBurnsPreset(1.0f, 1.3f, 0f, 0f, 0f, 0f),
    // Slow zoom out to center
    KenBurnsPreset(1.3f, 1.0f, 0f, 0f, 0f, 0f),
    // Zoom in, drift left → right
    KenBurnsPreset(1.1f, 1.25f, -PAN_FRACTION, 0f, PAN_FRACTION, 0f),
    // Zoom in, drift right → left
    KenBurnsPreset(1.1f, 1.25f, PAN_FRACTION, 0f, -PAN_FRACTION, 0f),
    // Zoom in, drift top → bottom
    KenBurnsPreset(1.1f, 1.2f, 0f, -PAN_FRACTION, 0f, PAN_FRACTION),
    // Zoom out, drift bottom → top
    KenBurnsPreset(1.2f, 1.05f, 0f, PAN_FRACTION, 0f, -PAN_FRACTION)
)

@Composable
fun KenBurnsImage(
    url: String,
    isActive: Boolean,
    durationMs: Int = KEN_BURNS_DURATION_MS,
    thumbnailUrl: String? = null,
    modifier: Modifier = Modifier
) {
    val progress = remember { Animatable(0f) }
    var widthPx by remember { mutableStateOf(0f) }
    var heightPx by remember { mutableStateOf(0f) }

    // Pick a random preset per URL
    val preset = remember(url) { presets[Random.nextInt(presets.size)] }

    LaunchedEffect(isActive, url, durationMs) {
        if (isActive) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMs,
                    easing = LinearEasing
                )
            )
        }
    }

    // Pull the grid-side thumbnail out of Coil's memory cache as an instant
    // placeholder so page advances during slideshow don't flash empty while the
    // high-res image decodes. Same pattern as ImageContent in AssetPageContent.
    val context = LocalPlatformContext.current
    val imageRequest = remember(context, url, thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(url)
            .apply { if (thumbnailUrl != null) placeholderMemoryCacheKey(thumbnailUrl) }
            .crossfade(200)
            .build()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                widthPx = it.width.toFloat()
                heightPx = it.height.toFloat()
            }
    ) {
        val p = progress.value
        val scale = preset.startScale + (preset.endScale - preset.startScale) * p
        val panX = widthPx * (preset.startPanX + (preset.endPanX - preset.startPanX) * p)
        val panY = heightPx * (preset.startPanY + (preset.endPanY - preset.startPanY) * p)

        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = panX
                    translationY = panY
                }
        )
    }
}
