package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class TimelineBucket(
    val displayLabel: String,
    val timeBucket: String,
    val count: Int
)
