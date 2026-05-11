package com.udnahc.immichgallery

import androidx.compose.runtime.Composable
import com.udnahc.immichgallery.di.platformModule
import com.udnahc.immichgallery.di.sharedModule
import org.koin.compose.KoinApplication

@Composable
actual fun AppKoin(content: @Composable () -> Unit) {
    KoinApplication(application = { modules(sharedModule, platformModule()) }) {
        content()
    }
}
