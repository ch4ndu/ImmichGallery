package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timeline_assets",
    indices = [Index("timeBucket")]
)
data class TimelineAssetEntity(
    @PrimaryKey val id: String,
    val timeBucket: String,
    val type: String,
    val fileName: String,
    val createdAt: String,
    val thumbnailUrl: String,
    val originalUrl: String,
    val videoPlaybackUrl: String,
    val isFavorite: Boolean,
    val stackCount: Int,
    val aspectRatio: Float,
    val sortOrder: Int
)
