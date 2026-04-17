package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "album_asset_refs",
    primaryKeys = ["albumId", "assetId"],
    indices = [Index("assetId")]
)
data class AlbumAssetCrossRef(
    val albumId: String,
    val assetId: String,
    val sortOrder: Int
)
