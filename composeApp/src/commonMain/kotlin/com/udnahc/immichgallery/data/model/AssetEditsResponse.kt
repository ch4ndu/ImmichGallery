package com.udnahc.immichgallery.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Response from `GET /api/assets/{id}/edits`. Returns the sequence of edit
 * actions (crop, rotate, mirror) that Immich applies non-destructively on top
 * of the original asset when generating thumbnails / previews / downloads.
 *
 * Action parameters are polymorphic. Rather than using kotlinx.serialization's
 * polymorphism machinery (which requires a discriminator setup that Immich
 * isn't using), each parameter is optional; only the one matching the action
 * name is populated.
 */
@Immutable
@Serializable
data class AssetEditsResponse(
    val assetId: String = "",
    val edits: List<AssetEditActionItem> = emptyList()
)

@Immutable
@Serializable
data class AssetEditActionItem(
    val id: String = "",
    val action: String,
    val parameters: AssetEditActionParameters = AssetEditActionParameters()
)

/**
 * Unified parameters bag — Immich's API returns different shapes per action,
 * but all fields we care about are numeric and present in only one action
 * type. Unused fields are null for irrelevant actions.
 */
@Immutable
@Serializable
data class AssetEditActionParameters(
    // Crop (post-rotation image coordinates):
    val x: Int? = null,
    val y: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    // Rotate:
    val angle: Int? = null,
    // Mirror has no parameters.
)
