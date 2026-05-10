package com.udnahc.immichgallery.di

import coil3.ImageLoader
import coil3.PlatformContext
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import io.ktor.client.HttpClient

expect fun createImageLoader(
    context: PlatformContext,
    httpClient: HttpClient,
    serverConfigRepository: ServerConfigRepository
): ImageLoader
