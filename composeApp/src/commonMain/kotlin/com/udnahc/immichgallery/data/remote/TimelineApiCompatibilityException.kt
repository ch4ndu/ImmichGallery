package com.udnahc.immichgallery.data.remote

class TimelineApiCompatibilityException(
    val serverVersion: String,
    val endpoint: String,
    cause: Throwable
) : IllegalStateException(
    "Immich $serverVersion returned an incompatible Timeline response from $endpoint",
    cause
)
