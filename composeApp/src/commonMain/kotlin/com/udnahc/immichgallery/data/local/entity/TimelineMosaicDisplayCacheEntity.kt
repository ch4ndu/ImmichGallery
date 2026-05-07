package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "timeline_mosaic_display_cache",
    primaryKeys = [
        "timeBucket",
        "groupMode",
        "sectionKey",
        "columnCount",
        "familiesKey",
        "assetFingerprint",
        "availableWidthKey",
        "displayVersion"
    ],
    indices = [
        Index("timeBucket"),
        Index("groupMode", "columnCount", "familiesKey", "availableWidthKey", "displayVersion", "timeBucket")
    ]
)
data class TimelineMosaicDisplayCacheEntity(
    val timeBucket: String,
    val groupMode: String,
    val sectionKey: String,
    val columnCount: Int,
    val familiesKey: String,
    val assetFingerprint: String,
    val availableWidthKey: Int,
    val displayVersion: Int,
    val itemsJson: String,
    val displayItemCount: Int,
    val placeholderHeight: Float,
    val maxRowHeightKey: Int,
    val spacingKey: Int,
    val updatedAt: Long
)
