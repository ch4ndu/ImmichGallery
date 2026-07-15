package com.udnahc.immichgallery

import androidx.compose.runtime.Composable
import com.udnahc.immichgallery.di.platformModule
import com.udnahc.immichgallery.di.sharedModule
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration

@Composable
actual fun AppKoin(content: @Composable () -> Unit) {
    KoinApplication(configuration = koinConfiguration { modules(sharedModule, platformModule()) }) {
        content()
    }
}
