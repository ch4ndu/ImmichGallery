package com.udnahc.immichgallery.di

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.CachePolicy
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File

private const val DECODE_PARALLELISM = 4

internal actual fun stableImageLoaderContext(context: PlatformContext): PlatformContext = context

@OptIn(ExperimentalCoilApi::class, ExperimentalCoroutinesApi::class)
actual fun createImageLoader(
    context: PlatformContext,
    httpClient: HttpClient,
    serverConfigRepository: ServerConfigRepository
): ManagedImageLoader {
    val imageClient = createAuthenticatedImageHttpClient(httpClient, serverConfigRepository)
    val cacheDirectory = File(
        System.getProperty("user.home"),
        ".immichgallery/cache/images"
    ).toOkioPath()
    FileSystem.SYSTEM.createDirectories(cacheDirectory)
    val imageLoader = ImageLoader.Builder(context)
        .components {
            add(KtorNetworkFetcherFactory(httpClient = imageClient))
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
        closeOwnedResources = imageClient::close
    )
}
