package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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

private const val PAN_FRACTION = 0.04f

/**
 * Each preset defines start and end states for scale and pan direction.
 * Pan values are fractions of the container size, applied based on progress.
 */
internal data class KenBurnsPreset(
    val startScale: Float,
    val endScale: Float,
    val startPanX: Float, // fraction of width
    val startPanY: Float, // fraction of height
    val endPanX: Float,
    val endPanY: Float
)

internal data class KenBurnsTransform(
    val scale: Float,
    val translationX: Float,
    val translationY: Float
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
    progress: () -> Float,
    motionEnabled: Boolean = true,
    thumbnailCacheKey: String? = null,
    onReady: () -> Unit = {},
    onError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableStateOf(0f) }
    var heightPx by remember { mutableStateOf(0f) }

    // Pick a random preset per URL
    val preset = remember(url) { presets[Random.nextInt(presets.size)] }

    // Pull the grid-side thumbnail out of Coil's memory cache as an instant
    // placeholder so page advances during slideshow don't flash empty while the
    // high-res image decodes. Same pattern as ImageContent in AssetPageContent.
    val context = LocalPlatformContext.current
    val imageRequest = remember(context, url, thumbnailCacheKey) {
        ImageRequest.Builder(context)
            .data(url)
            .apply { if (thumbnailCacheKey != null) placeholderMemoryCacheKey(thumbnailCacheKey) }
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
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            onSuccess = { onReady() },
            onError = { onError() },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val transform = interpolateKenBurnsTransform(
                        preset = preset,
                        widthPx = widthPx,
                        heightPx = heightPx,
                        progress = progress()
                    )
                    scaleX = if (motionEnabled) transform.scale else 1f
                    scaleY = if (motionEnabled) transform.scale else 1f
                    translationX = if (motionEnabled) transform.translationX else 0f
                    translationY = if (motionEnabled) transform.translationY else 0f
                }
        )
    }
}

internal fun interpolateKenBurnsTransform(
    preset: KenBurnsPreset,
    widthPx: Float,
    heightPx: Float,
    progress: Float
): KenBurnsTransform {
    val p = progress.coerceIn(0f, 1f)
    return KenBurnsTransform(
        scale = preset.startScale + (preset.endScale - preset.startScale) * p,
        translationX = widthPx * (preset.startPanX + (preset.endPanX - preset.startPanX) * p),
        translationY = heightPx * (preset.startPanY + (preset.endPanY - preset.startPanY) * p)
    )
}
