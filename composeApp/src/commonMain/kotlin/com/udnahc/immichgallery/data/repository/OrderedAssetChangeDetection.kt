package com.udnahc.immichgallery.data.repository

import com.udnahc.immichgallery.data.local.entity.AssetEntity

// Detail and Timeline syncs can write the same rows repeatedly. Layout
// invalidation must follow ordered, grid-visible content instead of Room write
// success, and it must ignore cache bookkeeping such as editsResolved.
internal fun orderedAssetsChanged(
    before: List<AssetEntity>,
    after: List<AssetEntity>
): Boolean =
    before.map { it.orderedFingerprint() } != after.map { it.orderedFingerprint() }

private data class OrderedAssetFingerprint(
    val id: String,
    val type: String,
    val fileName: String,
    val createdAt: String,
    val isFavorite: Boolean,
    val stackCount: Int,
    val aspectRatio: Float,
    val isEdited: Boolean
)

private fun AssetEntity.orderedFingerprint(): OrderedAssetFingerprint =
    OrderedAssetFingerprint(
        id = id,
        type = type,
        fileName = fileName,
        createdAt = createdAt,
        isFavorite = isFavorite,
        stackCount = stackCount,
        aspectRatio = aspectRatio,
        isEdited = isEdited
    )
