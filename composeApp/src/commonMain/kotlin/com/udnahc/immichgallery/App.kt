package com.udnahc.immichgallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import coil3.compose.setSingletonImageLoaderFactory
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.di.createImageLoader
import com.udnahc.immichgallery.ui.navigation.AppNavigation
import com.udnahc.immichgallery.ui.theme.ImmichGalleryTheme
import io.ktor.client.HttpClient
import org.koin.compose.koinInject

val LocalAppActive = compositionLocalOf { true }

@Composable
fun App() {
    val isAppActive by AppLifecycle.isForeground.collectAsState()
    AppKoin {
        ImmichGalleryTheme {
            val httpClient: HttpClient = koinInject()
            val serverConfigRepository: ServerConfigRepository = koinInject()
            setSingletonImageLoaderFactory { context ->
                createImageLoader(context, httpClient, serverConfigRepository)
            }
            CompositionLocalProvider(LocalAppActive provides isAppActive) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()
                }
            }
        }
    }
}
