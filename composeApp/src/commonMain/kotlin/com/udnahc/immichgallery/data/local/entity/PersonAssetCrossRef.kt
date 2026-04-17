package com.udnahc.immichgallery.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "person_asset_refs",
    primaryKeys = ["personId", "assetId"],
    indices = [Index("assetId")]
)
data class PersonAssetCrossRef(
    val personId: String,
    val assetId: String,
    val sortOrder: Int
)
