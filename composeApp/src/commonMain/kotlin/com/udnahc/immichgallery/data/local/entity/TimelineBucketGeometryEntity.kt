package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "timeline_bucket_geometry",
    primaryKeys = [
        "timeBucket",
        "groupMode",
        "columnCount",
        "familiesKey",
        "assetFingerprint",
        "availableWidthKey",
        "geometryVersion"
    ],
    indices = [
        Index("timeBucket"),
        Index(
            "groupMode",
            "columnCount",
            "familiesKey",
            "availableWidthKey",
            "geometryVersion",
            "timeBucket"
        )
    ]
)
data class TimelineBucketGeometryEntity(
    val timeBucket: String,
    val groupMode: String,
    val columnCount: Int,
    val familiesKey: String,
    val assetFingerprint: String,
    val availableWidthKey: Int,
    val geometryVersion: Int,
    val placeholderHeight: Float,
    val displayItemCount: Int,
    val maxRowHeightKey: Int,
    val spacingKey: Int,
    val updatedAt: Long
)
