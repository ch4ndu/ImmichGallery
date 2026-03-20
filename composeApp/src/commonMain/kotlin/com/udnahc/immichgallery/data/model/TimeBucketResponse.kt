package com.udnahc.immichgallery.data.model

import kotlinx.serialization.Serializable

@Serializable
data class TimeBucketResponse(
    val timeBucket: String,
    val count: Int
)
