package com.udnahc.immichgallery.ui.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessController
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessResult
import com.udnahc.immichgallery.domain.model.EndpointHostResolver
import com.udnahc.immichgallery.domain.model.EndpointNetworkScope
import com.udnahc.immichgallery.domain.model.LocalNetworkEndpointClassifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress

internal class AndroidLocalNetworkAccessController(
    private val context: Context
) : LocalNetworkAccessController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestMutex = Mutex()
    private val endpointClassifier = LocalNetworkEndpointClassifier(AndroidEndpointHostResolver())

    @Volatile
    private var permissionLauncher: ActivityResultLauncher<String>? = null

    @Volatile
    private var pendingRequest: CompletableDeferred<LocalNetworkAccessResult>? = null

    fun bindTo(activity: ComponentActivity) {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            pendingRequest?.complete(
                if (granted) LocalNetworkAccessResult.Granted else LocalNetworkAccessResult.Denied
            )
        }
    }

    override suspend fun ensureAccess(serverUrl: String): LocalNetworkAccessResult {
        if (Build.VERSION.SDK_INT < ANDROID_17_API_LEVEL) return LocalNetworkAccessResult.NotRequired

        return when (endpointClassifier.classify(serverUrl)) {
            EndpointNetworkScope.Local -> requestAccess()
            EndpointNetworkScope.Public,
            EndpointNetworkScope.Unknown -> LocalNetworkAccessResult.NotRequired
        }
    }

    override fun openAppSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private suspend fun requestAccess(): LocalNetworkAccessResult {
        if (hasAccess()) return LocalNetworkAccessResult.Granted

        return requestMutex.withLock {
            if (hasAccess()) return@withLock LocalNetworkAccessResult.Granted
            val launcher = permissionLauncher ?: return@withLock LocalNetworkAccessResult.Unavailable
            val request = CompletableDeferred<LocalNetworkAccessResult>()
            pendingRequest = request
            mainHandler.post {
                if (pendingRequest === request) {
                    launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            }
            try {
                request.await()
            } finally {
                if (pendingRequest === request) pendingRequest = null
            }
        }
    }

    private fun hasAccess(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val ANDROID_17_API_LEVEL = 37
    }
}

private class AndroidEndpointHostResolver : EndpointHostResolver {
    override suspend fun resolve(host: String): List<String>? =
        runCatching { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } }.getOrNull()
}
