package com.udnahc.immichgallery

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coil3.compose.setSingletonImageLoaderFactory
import com.udnahc.immichgallery.di.createImageLoader
import com.udnahc.immichgallery.di.platformModule
import com.udnahc.immichgallery.di.sharedModule
import com.udnahc.immichgallery.ui.navigation.AppNavigation
import com.udnahc.immichgallery.ui.theme.ImmichGalleryTheme
import io.ktor.client.HttpClient
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject

@Composable
fun App() {
    KoinApplication(application = { modules(sharedModule, platformModule()) }) {
        ImmichGalleryTheme {
            val httpClient: HttpClient = koinInject()
            setSingletonImageLoaderFactory { context ->
                createImageLoader(context, httpClient)
            }
            Surface(modifier = Modifier.fillMaxSize()) {
                AppNavigation()
            }
        }
    }
}
