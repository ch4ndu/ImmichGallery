package com.udnahc.immichgallery.domain.model

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.max

/**
 * Extracts approximate aspect ratio from a thumbhash string.
 * Thumbhash encodes DCT component counts (lx, ly) which approximate
 * the image's width/height ratio without decoding the full image.
 *
 * Returns null if the thumbhash is invalid or too short.
 */
@OptIn(ExperimentalEncodingApi::class)
fun thumbhashToAspectRatio(thumbhash: String): Float? {
    val bytes = try {
        Base64.decode(thumbhash)
    } catch (_: Exception) {
        return null
    }
    if (bytes.size < 5) return null

    val header24 = (bytes[0].toInt() and 0xFF) or
        ((bytes[1].toInt() and 0xFF) shl 8) or
        ((bytes[2].toInt() and 0xFF) shl 16)
    val header16 = (bytes[3].toInt() and 0xFF) or
        ((bytes[4].toInt() and 0xFF) shl 8)

    val hasAlpha = (header24 shr 23) != 0
    val isLandscape = (header16 shr 15) != 0

    val lx = max(3, if (isLandscape) { if (hasAlpha) 5 else 7 } else { header16 and 7 })
    val ly = max(3, if (isLandscape) { header16 and 7 } else { if (hasAlpha) 5 else 7 })

    return lx.toFloat() / ly.toFloat()
}
