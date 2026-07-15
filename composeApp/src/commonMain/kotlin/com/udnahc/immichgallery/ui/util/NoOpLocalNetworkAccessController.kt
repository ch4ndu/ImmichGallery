package com.udnahc.immichgallery.ui.util

import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessController
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessResult

internal class NoOpLocalNetworkAccessController : LocalNetworkAccessController {
    override suspend fun ensureAccess(serverUrl: String): LocalNetworkAccessResult =
        LocalNetworkAccessResult.NotRequired

    override fun openAppSettings() = Unit
}
