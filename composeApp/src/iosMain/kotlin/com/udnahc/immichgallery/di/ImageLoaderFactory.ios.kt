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

private const val DECODE_PARALLELISM = 4

@OptIn(ExperimentalCoilApi::class, ExperimentalCoroutinesApi::class)
actual fun createImageLoader(
    context: PlatformContext,
    httpClient: HttpClient,
    serverConfigRepository: ServerConfigRepository
): ImageLoader {
    return ImageLoader.Builder(context)
        .components {
            add(KtorNetworkFetcherFactory(httpClient = httpClient))
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
                .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "image_cache")
                .maxSizePercent(0.20)
                .build()
        }
        .build()
}
