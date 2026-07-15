package com.udnahc.immichgallery.domain.action.auth

class OpenLocalNetworkSettingsAction internal constructor(
    private val localNetworkAccessController: LocalNetworkAccessController
) {
    operator fun invoke() {
        localNetworkAccessController.openAppSettings()
    }
}
