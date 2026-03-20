package com.udnahc.immichgallery.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UserResponse(
    val id: String = "",
    val email: String = "",
    val name: String = ""
)
