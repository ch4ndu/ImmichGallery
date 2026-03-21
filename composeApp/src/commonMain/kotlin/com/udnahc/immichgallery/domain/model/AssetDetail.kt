package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class AssetDetail(
    val id: String,
    val fileName: String,
    val dateTime: String?,
    val cameraMake: String?,
    val cameraModel: String?,
    val lensModel: String?,
    val focalLength: Double?,
    val aperture: Double?,
    val shutterSpeed: String?,
    val iso: Int?,
    val latitude: Double?,
    val longitude: Double?,
    val city: String?,
    val state: String?,
    val country: String?,
    val fileSizeInByte: Long?,
    val width: Int?,
    val height: Int?,
    val description: String?,
    val people: List<AssetDetailPerson>
)

@Immutable
data class AssetDetailPerson(
    val id: String,
    val name: String,
    val thumbnailUrl: String
)
