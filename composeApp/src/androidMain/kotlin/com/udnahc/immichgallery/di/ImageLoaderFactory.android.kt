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

private const val DECODE_PARALLELISM = 4

@OptIn(ExperimentalCoroutinesApi::class)
actual fun createImageLoader(
    context: PlatformContext,
    httpClient: HttpClient,
    serverConfigRepository: ServerConfigRepository
): ImageLoader {
    val imageClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val apiKey = serverConfigRepository.getApiKey()
            val request = if (apiKey.isBlank()) {
                chain.request()
            } else {
                chain.request()
                    .newBuilder()
                    .header("x-api-key", apiKey)
                    .build()
            }
            chain.proceed(request)
        }
        .build()
    return ImageLoader.Builder(context)
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
                .directory(FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "image_cache")
                .maxSizePercent(0.20)
                .build()
        }
        .build()
}
