package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "detail_mosaic_display_cache",
    primaryKeys = [
        "ownerType",
        "ownerId",
        "groupSize",
        "columnCount",
        "sectionIndex",
        "sectionKey",
        "familiesKey",
        "assetFingerprint",
        "availableWidthKey",
        "cellHeightKey",
        "maxRowHeightKey",
        "spacingKey",
        "displayVersion"
    ],
    indices = [
        Index("ownerType", "ownerId"),
        Index(
            "ownerType",
            "ownerId",
            "groupSize",
            "columnCount",
            "familiesKey",
            "availableWidthKey",
            "cellHeightKey",
            "maxRowHeightKey",
            "spacingKey",
            "displayVersion"
        )
    ]
)
data class DetailMosaicDisplayCacheEntity(
    val ownerType: String,
    val ownerId: String,
    val groupSize: String,
    val columnCount: Int,
    val sectionIndex: Int,
    val sectionKey: String,
    val familiesKey: String,
    val assetFingerprint: String,
    val availableWidthKey: Int,
    val cellHeightKey: Int,
    val maxRowHeightKey: Int,
    val spacingKey: Int,
    val displayVersion: Int,
    val itemsJson: String,
    val displayItemCount: Int,
    val placeholderHeight: Float,
    val updatedAt: Long
)
