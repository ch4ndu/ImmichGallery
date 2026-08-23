package com.udnahc.immichgallery.di

import com.russhwolf.settings.Settings
import com.udnahc.immichgallery.data.local.createDatabase
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessController
import com.udnahc.immichgallery.ui.util.NoOpLocalNetworkAccessController
import com.udnahc.immichgallery.ui.util.NoOpSyncActivityNotifier
import com.udnahc.immichgallery.ui.util.PlatformSyncActivityNotifier
import com.udnahc.immichgallery.ui.util.PlatformTimelineManualSyncStarter
import com.udnahc.immichgallery.ui.util.TimelineManualSyncStarter
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single<Settings> { Settings() }
    single { createDatabase() }
    single { get<com.udnahc.immichgallery.data.local.AppDatabase>().timelineDao() }
    single { get<com.udnahc.immichgallery.data.local.AppDatabase>().assetDao() }
    single { get<com.udnahc.immichgallery.data.local.AppDatabase>().syncMetadataDao() }
    single { get<com.udnahc.immichgallery.data.local.AppDatabase>().albumDao() }
    single { get<com.udnahc.immichgallery.data.local.AppDatabase>().personDao() }
    single { get<com.udnahc.immichgallery.data.local.AppDatabase>().detailMosaicCacheDao() }
    single<LocalNetworkAccessController> { NoOpLocalNetworkAccessController() }
    single<PlatformSyncActivityNotifier> { NoOpSyncActivityNotifier() }
    single<TimelineManualSyncStarter> { PlatformTimelineManualSyncStarter() }
}
