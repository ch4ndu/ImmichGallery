package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "timeline_asset_refs",
    primaryKeys = ["timeBucket", "assetId"],
    indices = [Index("assetId")]
)
data class TimelineAssetCrossRef(
    val timeBucket: String,
    val assetId: String,
    val sortOrder: Int
)
