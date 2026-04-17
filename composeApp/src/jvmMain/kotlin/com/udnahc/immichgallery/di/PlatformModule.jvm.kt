package com.udnahc.immichgallery.di

import com.udnahc.immichgallery.data.local.createDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { createDatabase() }
    single { get<com.udnahc.immichgallery.data.local.AppDatabase>().timelineDao() }
    single { get<com.udnahc.immichgallery.data.local.AppDatabase>().assetDao() }
    single { get<com.udnahc.immichgallery.data.local.AppDatabase>().syncMetadataDao() }
    single { get<com.udnahc.immichgallery.data.local.AppDatabase>().albumDao() }
    single { get<com.udnahc.immichgallery.data.local.AppDatabase>().personDao() }
}
