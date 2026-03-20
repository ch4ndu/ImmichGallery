package com.udnahc.immichgallery.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PersonResponse(
    val id: String,
    val name: String = "",
    val thumbnailPath: String = "",
    val isHidden: Boolean = false
)

@Serializable
data class PeopleResponse(
    val people: List<PersonResponse> = emptyList(),
    val total: Int = 0
)
