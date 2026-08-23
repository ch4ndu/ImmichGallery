package com.udnahc.immichgallery.di

import coil3.ImageLoader
import coil3.PlatformContext
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.header
import io.ktor.http.Url

class ManagedImageLoader internal constructor(
    val imageLoader: ImageLoader,
    private val stopImageLoader: () -> Unit = imageLoader::shutdown,
    private val closeDiskCache: () -> Unit = {
        imageLoader.diskCache?.shutdown()
    },
    private val closeOwnedResources: () -> Unit = {}
) {
    fun shutdown() {
        stopImageLoader()
        closeDiskCache()
        closeOwnedResources()
    }
}

expect fun createImageLoader(
    context: PlatformContext,
    httpClient: HttpClient,
    serverConfigRepository: ServerConfigRepository
): ManagedImageLoader

internal expect fun stableImageLoaderContext(context: PlatformContext): PlatformContext

internal fun createAuthenticatedImageHttpClient(
    baseClient: HttpClient,
    serverConfigRepository: ServerConfigRepository
): HttpClient {
    val exactOriginAuth = createClientPlugin("ExactOriginImageAuth") {
        onRequest { request, _ ->
            val session = serverConfigRepository.currentSession()
            if (session.apiKey.isNotBlank() && isExactConfiguredOrigin(
                    requestUrl = request.url.build(),
                    configuredServerUrl = session.serverUrl
                )
            ) {
                request.header("x-api-key", session.apiKey)
            }
        }
    }
    return baseClient.config {
        followRedirects = false
        install(exactOriginAuth)
    }
}

internal fun isExactConfiguredOrigin(
    requestUrl: Url,
    configuredServerUrl: String
): Boolean {
    val configured = try {
        Url(configuredServerUrl.trimEnd('/'))
    } catch (_: Throwable) {
        return false
    }
    return requestUrl.protocol == configured.protocol &&
            requestUrl.host.equals(configured.host, ignoreCase = true) &&
            requestUrl.port == configured.port
}
