package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "album_assets",
    indices = [Index("albumId")]
)
data class AlbumAssetEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val id: String,
    val type: String,
    val fileName: String,
    val createdAt: String,
    val isFavorite: Boolean,
    val albumId: String,
    val sortOrder: Int
)
