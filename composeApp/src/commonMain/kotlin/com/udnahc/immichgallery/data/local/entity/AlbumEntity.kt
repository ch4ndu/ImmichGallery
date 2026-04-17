package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    val name: String,
    val assetCount: Int,
    val thumbnailAssetId: String?,
    val updatedAt: String
)
