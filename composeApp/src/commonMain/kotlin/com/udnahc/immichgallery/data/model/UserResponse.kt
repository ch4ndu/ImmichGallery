package com.udnahc.immichgallery.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class UserResponse(
    val id: String = "",
    val email: String = "",
    val name: String = ""
)
