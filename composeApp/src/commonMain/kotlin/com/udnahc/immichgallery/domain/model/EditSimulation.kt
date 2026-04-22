package com.udnahc.immichgallery.domain.model

import com.udnahc.immichgallery.data.model.AssetEditActionItem

/**
 * Simulates Immich's non-destructive edit pipeline on the original (width,
 * height) to compute the final displayed dimensions. Immich applies edits in
 * the order stored, producing the thumbnail / preview / download bytes at the
 * resulting size.
 *
 * Supported actions (matching Immich server's `editing.dto.ts`):
 * - `crop`: `{x, y, width, height}` — new dimensions become `(width, height)`.
 * - `rotate`: `{angle}` — only 90° multiples affect shape; odd multiples swap
 *   width and height.
 * - `mirror`: no dimension change.
 *
 * Returns null if the input dimensions are invalid (either non-positive or
 * missing). Returns the final `(width, height)` as an `IntArray` of size 2 on
 * success.
 */
fun simulateEditDimensions(
    originalWidth: Int?,
    originalHeight: Int?,
    edits: List<AssetEditActionItem>
): IntArray? {
    val w0 = originalWidth ?: return null
    val h0 = originalHeight ?: return null
    if (w0 <= 0 || h0 <= 0) return null

    var w = w0
    var h = h0
    for (edit in edits) {
        when (edit.action.uppercase()) {
            "CROP" -> {
                val cw = edit.parameters.width ?: continue
                val ch = edit.parameters.height ?: continue
                if (cw <= 0 || ch <= 0) continue
                w = cw
                h = ch
            }
            "ROTATE" -> {
                val angle = edit.parameters.angle ?: continue
                // Normalize to [0, 360). Only 90 / 270 swap dimensions.
                val normalized = ((angle % 360) + 360) % 360
                if (normalized == 90 || normalized == 270) {
                    val t = w
                    w = h
                    h = t
                }
            }
            "MIRROR" -> {
                // Flip has no effect on dimensions.
            }
            // Unknown action: ignore, don't corrupt the pipeline.
        }
    }
    return intArrayOf(w, h)
}
