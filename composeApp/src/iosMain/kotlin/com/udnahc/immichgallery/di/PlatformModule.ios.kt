package com.udnahc.immichgallery.di

import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.local.getRoomDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<AppDatabase> { getRoomDatabase() }
}
