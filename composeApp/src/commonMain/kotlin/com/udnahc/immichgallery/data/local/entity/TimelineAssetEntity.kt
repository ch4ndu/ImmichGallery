package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "timeline_assets",
    indices = [Index("timeBucket"), Index("createdAt", "id")]
)
data class TimelineAssetEntity(
    @PrimaryKey val id: String,
    val type: String,
    val fileName: String,
    val createdAt: String,
    val isFavorite: Boolean,
    val timeBucket: String,
    val stackId: String? = null,
    val stackCount: Int = 0
)
