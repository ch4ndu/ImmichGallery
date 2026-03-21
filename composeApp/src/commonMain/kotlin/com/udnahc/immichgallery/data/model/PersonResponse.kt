package com.udnahc.immichgallery.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class PersonResponse(
    val id: String,
    val name: String = "",
    val thumbnailPath: String = "",
    val isHidden: Boolean = false
)

@Immutable
@Serializable
data class PeopleResponse(
    val people: List<PersonResponse> = emptyList(),
    val total: Int = 0
)
