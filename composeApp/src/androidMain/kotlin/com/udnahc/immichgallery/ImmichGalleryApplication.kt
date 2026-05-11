package com.udnahc.immichgallery

import android.app.Application
import com.udnahc.immichgallery.di.initPlatformContext
import com.udnahc.immichgallery.di.platformModule
import com.udnahc.immichgallery.di.sharedModule
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class ImmichGalleryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initPlatformContext(this)
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(sharedModule, platformModule())
            }
        }
    }
}
