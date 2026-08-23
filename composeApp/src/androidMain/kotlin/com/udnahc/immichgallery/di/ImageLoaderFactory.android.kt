package com.udnahc.immichgallery.di

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.OkHttpClient
import okio.FileSystem
import okio.Path.Companion.toOkioPath

private const val DECODE_PARALLELISM = 4

internal actual fun stableImageLoaderContext(context: PlatformContext): PlatformContext =
    context.applicationContext

@OptIn(ExperimentalCoroutinesApi::class)
actual fun createImageLoader(
    context: PlatformContext,
    httpClient: HttpClient,
    serverConfigRepository: ServerConfigRepository
): ManagedImageLoader {
    val imageClient = buildAndroidImageClient(serverConfigRepository)
    val cacheDirectory = context.cacheDir.toOkioPath() / "immichgallery_images"
    FileSystem.SYSTEM.createDirectories(cacheDirectory)
    val imageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(imageClient))
        }
        .decoderCoroutineContext(Dispatchers.Default.limitedParallelism(DECODE_PARALLELISM))
        .memoryCachePolicy(CachePolicy.ENABLED)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, percent = 0.25)
                .build()
        }
        .diskCachePolicy(CachePolicy.ENABLED)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDirectory)
                .maxSizePercent(0.20)
                .build()
        }
        .build()
    return ManagedImageLoader(
        imageLoader = imageLoader,
        closeOwnedResources = {
            imageClient.dispatcher.cancelAll()
            imageClient.connectionPool.evictAll()
            imageClient.dispatcher.executorService.shutdown()
        }
    )
}

internal fun buildAndroidImageClient(
    serverConfigRepository: ServerConfigRepository
): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            val session = serverConfigRepository.currentSession()
            val request = if (session.apiKey.isBlank() || !isExactConfiguredOrigin(
                    requestUrl = io.ktor.http.Url(chain.request().url.toString()),
                    configuredServerUrl = session.serverUrl
                )
            ) {
                chain.request()
            } else {
                chain.request()
                    .newBuilder()
                    .header("x-api-key", session.apiKey)
                    .build()
            }
            chain.proceed(request)
        }
        .build()
