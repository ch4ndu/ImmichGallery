package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "timeline_mosaic_assignments",
    primaryKeys = ["timeBucket", "groupMode", "sectionKey", "columnCount", "familiesKey"],
    indices = [Index("timeBucket")]
)
data class TimelineMosaicAssignmentEntity(
    val timeBucket: String,
    val groupMode: String,
    val sectionKey: String,
    val columnCount: Int,
    val familiesKey: String,
    val assetFingerprint: String,
    val assignmentsJson: String,
    val updatedAt: Long
)
