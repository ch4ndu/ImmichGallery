package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "timeline_mosaic_geometry",
    primaryKeys = [
        "timeBucket",
        "groupMode",
        "sectionKey",
        "columnCount",
        "familiesKey",
        "assetFingerprint",
        "availableWidthKey",
        "geometryVersion"
    ],
    indices = [
        Index("timeBucket"),
        Index("groupMode", "columnCount", "familiesKey", "availableWidthKey", "geometryVersion", "timeBucket")
    ]
)
data class TimelineMosaicGeometryEntity(
    val timeBucket: String,
    val groupMode: String,
    val sectionKey: String,
    val columnCount: Int,
    val familiesKey: String,
    val assetFingerprint: String,
    val availableWidthKey: Int,
    val geometryVersion: Int,
    val placeholderHeight: Float,
    val displayItemCount: Int,
    val maxRowHeightKey: Int,
    val spacingKey: Int,
    val geometryBandsJson: String,
    val updatedAt: Long
)
