package com.udnahc.immichgallery.domain.action.auth

class EnsureLocalNetworkAccessAction internal constructor(
    private val localNetworkAccessController: LocalNetworkAccessController
) {
    suspend operator fun invoke(serverUrl: String): LocalNetworkAccessResult =
        localNetworkAccessController.ensureAccess(serverUrl)
}
