package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "detail_mosaic_assignments",
    primaryKeys = [
        "ownerType",
        "ownerId",
        "groupSize",
        "columnCount",
        "sectionIndex",
        "sectionKey",
        "familiesKey",
        "assetFingerprint"
    ],
    indices = [
        Index("ownerType", "ownerId"),
        Index("ownerType", "ownerId", "groupSize", "columnCount", "familiesKey")
    ]
)
data class DetailMosaicAssignmentEntity(
    val ownerType: String,
    val ownerId: String,
    val groupSize: String,
    val columnCount: Int,
    val sectionIndex: Int,
    val sectionKey: String,
    val familiesKey: String,
    val assetFingerprint: String,
    val assignmentsJson: String,
    val updatedAt: Long
)
