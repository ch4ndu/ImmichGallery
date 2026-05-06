package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "detail_mosaic_aggregate_geometry",
    primaryKeys = [
        "ownerType",
        "ownerId",
        "groupSize",
        "columnCount",
        "familiesKey",
        "assetFingerprint",
        "availableWidthKey",
        "cellHeightKey",
        "maxRowHeightKey",
        "spacingKey",
        "geometryVersion"
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
            "geometryVersion"
        )
    ]
)
data class DetailMosaicAggregateGeometryEntity(
    val ownerType: String,
    val ownerId: String,
    val groupSize: String,
    val columnCount: Int,
    val familiesKey: String,
    val assetFingerprint: String,
    val availableWidthKey: Int,
    val cellHeightKey: Int,
    val maxRowHeightKey: Int,
    val spacingKey: Int,
    val geometryVersion: Int,
    val placeholderHeight: Float,
    val displayItemCount: Int,
    val updatedAt: Long
)
