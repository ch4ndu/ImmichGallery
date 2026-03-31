package com.udnahc.immichgallery.di

import android.content.Context
import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.local.createDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

private lateinit var appContext: Context

fun initPlatformContext(context: Context) {
    appContext = context.applicationContext
}

actual fun platformModule(): Module = module {
    single { createDatabase(appContext) }
    single { get<AppDatabase>().timelineDao() }
}
