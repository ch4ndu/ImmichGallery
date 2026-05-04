package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Person(
    val id: String,
    val name: String,
    val thumbnailUrl: String
)

@Immutable
data class PersonAssetsSyncResult(
    val changed: Boolean,
    val hasMore: Boolean = false
)
