package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey val id: String,
    val type: String,
    val fileName: String,
    val createdAt: String,
    val isFavorite: Boolean,
    val stackCount: Int,
    val aspectRatio: Float
)
