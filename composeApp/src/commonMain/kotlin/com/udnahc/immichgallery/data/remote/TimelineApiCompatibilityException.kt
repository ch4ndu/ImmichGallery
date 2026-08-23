package com.udnahc.immichgallery.data.remote

class TimelineApiCompatibilityException(
    val serverVersion: String,
    val endpoint: String,
    val category: TimelineCompatibilityCategory
) : IllegalStateException(
    "Immich $serverVersion returned an incompatible Timeline $category response from $endpoint"
)

enum class TimelineCompatibilityCategory {
    DECODING,
    SHAPE
}
