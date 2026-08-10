package com.udnahc.immichgallery.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class SearchServerFeaturesResponse(
    val search: Boolean? = null,
    val smartSearch: Boolean? = null,
    val ocr: Boolean? = null
)

@Immutable
@Serializable
data class SearchUserPreferencesResponse(
    val tags: SearchPreferenceEnabledResponse? = null,
    val ratings: SearchPreferenceEnabledResponse? = null
)

@Immutable
@Serializable
data class SearchPreferenceEnabledResponse(
    val enabled: Boolean? = null
)

@Immutable
@Serializable
data class SearchTagResponse(
    val id: String,
    val parentId: String? = null,
    val name: String,
    val value: String,
    val color: String? = null
)
