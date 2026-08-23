package com.udnahc.immichgallery.di

import android.content.Context
import androidx.activity.ComponentActivity
import com.russhwolf.settings.Settings
import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.local.createDatabase
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessController
import com.udnahc.immichgallery.ui.util.AndroidLocalNetworkAccessController
import com.udnahc.immichgallery.ui.util.AndroidSyncActivityNotifier
import com.udnahc.immichgallery.ui.util.PlatformSyncActivityNotifier
import com.udnahc.immichgallery.ui.util.PlatformTimelineManualSyncStarter
import com.udnahc.immichgallery.ui.util.TimelineManualSyncStarter
import org.koin.core.module.Module
import org.koin.core.context.GlobalContext
import org.koin.dsl.module

private lateinit var appContext: Context

fun initPlatformContext(context: Context) {
    appContext = context.applicationContext
}

fun bindLocalNetworkAccessController(activity: ComponentActivity) {
    GlobalContext.get().get<AndroidLocalNetworkAccessController>().bindTo(activity)
}

actual fun platformModule(): Module = module {
    single<Settings> { Settings() }
    single { createDatabase(appContext) }
    single { get<AppDatabase>().timelineDao() }
    single { get<AppDatabase>().assetDao() }
    single { get<AppDatabase>().syncMetadataDao() }
    single { get<AppDatabase>().albumDao() }
    single { get<AppDatabase>().personDao() }
    single { get<AppDatabase>().detailMosaicCacheDao() }
    single { AndroidLocalNetworkAccessController(appContext) }
    single<LocalNetworkAccessController> { get<AndroidLocalNetworkAccessController>() }
    single<PlatformSyncActivityNotifier> { AndroidSyncActivityNotifier(appContext) }
    single<TimelineManualSyncStarter> { PlatformTimelineManualSyncStarter(appContext) }
}
