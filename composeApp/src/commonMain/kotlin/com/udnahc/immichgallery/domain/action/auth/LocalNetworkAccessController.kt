package com.udnahc.immichgallery.domain.action.auth

internal interface LocalNetworkAccessController {
    suspend fun ensureAccess(serverUrl: String): LocalNetworkAccessResult

    fun openAppSettings()
}

enum class LocalNetworkAccessResult {
    Granted,
    Denied,
    NotRequired,
    Unavailable
}
