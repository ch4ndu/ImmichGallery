package com.udnahc.immichgallery.domain.action.auth

interface ServerScopedImageCache {
    suspend fun rotateAndClearAround(block: suspend () -> Unit)
}
