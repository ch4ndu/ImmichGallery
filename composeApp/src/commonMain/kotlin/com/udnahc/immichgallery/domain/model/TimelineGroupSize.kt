package com.udnahc.immichgallery.domain.model

import androidx.compose.runtime.Immutable

@Immutable
enum class TimelineGroupSize(val apiValue: String) {
    MONTH("MONTH"),
    DAY("DAY")
}
