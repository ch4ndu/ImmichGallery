package com.udnahc.immichgallery.ui.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.DnsResolver
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessController
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessResult
import com.udnahc.immichgallery.domain.model.BoundedEndpointHostResolver
import com.udnahc.immichgallery.domain.model.EndpointHostResolver
import com.udnahc.immichgallery.domain.model.EndpointNetworkScope
import com.udnahc.immichgallery.domain.model.LocalNetworkEndpointClassifier
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val legacyFallbackResolver = BoundedEndpointHostResolver(Dispatchers.IO) { host ->
        InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
    }

    override suspend fun resolve(host: String): List<String>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolveWithDnsResolver(host)
        } else {
            legacyFallbackResolver.resolve(host)
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun resolveWithDnsResolver(host: String): List<String>? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            val finished = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                cancellationSignal.cancel()
            }
            try {
                DnsResolver.getInstance().query(
                    null,
                    host,
                    DnsResolver.FLAG_EMPTY,
                    { command -> Dispatchers.IO.dispatch(EmptyCoroutineContext, Runnable { command.run() }) },
                    cancellationSignal,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            if (finished.compareAndSet(false, true) && continuation.isActive) {
                                continuation.resume(answer.mapNotNull { it.hostAddress })
                            }
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            if (finished.compareAndSet(false, true) && continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
                if (finished.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
}
