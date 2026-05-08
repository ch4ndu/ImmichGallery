package com.udnahc.immichgallery.di

import com.russhwolf.settings.Settings
import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.remote.HttpClientFactory
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.data.remote.createHttpClientEngine
import com.udnahc.immichgallery.data.repository.AlbumRepository
import com.udnahc.immichgallery.data.repository.AssetEditsEnricher
import com.udnahc.immichgallery.data.repository.AssetRepository
import com.udnahc.immichgallery.data.repository.AuthRepository
import com.udnahc.immichgallery.data.repository.DetailMosaicCacheRepository
import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.data.repository.SearchRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.data.repository.ServerStatusRepository
import com.udnahc.immichgallery.data.repository.TimelineMosaicCacheRepository
import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.action.auth.ClearServerConfigAction
import com.udnahc.immichgallery.domain.action.auth.MonitorServerStatusAction
import com.udnahc.immichgallery.domain.action.auth.SaveServerConfigAction
import com.udnahc.immichgallery.domain.action.detail.ClearDetailMosaicCacheAction
import com.udnahc.immichgallery.domain.action.detail.UpsertDetailMosaicArtifactsAction
import com.udnahc.immichgallery.domain.action.detail.UpsertDetailMosaicCacheAction
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.action.settings.SetTimelineGroupSizeAction
import com.udnahc.immichgallery.domain.action.settings.SetViewConfigAction
import com.udnahc.immichgallery.domain.action.timeline.ClearTimelineMosaicCacheAction
import com.udnahc.immichgallery.domain.action.timeline.LoadBucketAssetsAction
import com.udnahc.immichgallery.domain.action.timeline.PrepareTimelineMosaicCacheAction
import com.udnahc.immichgallery.domain.action.timeline.SyncAllTimelineAssetsAction
import com.udnahc.immichgallery.domain.action.timeline.TimelineBucketSnapshotReader
import com.udnahc.immichgallery.domain.model.TimelineMosaicArtifactBuilder
import com.udnahc.immichgallery.domain.usecase.timeline.GetBucketAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumDetailUseCase
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumsUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetLoginStatusUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetServerStatusUseCase
import com.udnahc.immichgallery.domain.usecase.auth.ValidateServerUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.detail.GetDetailMosaicArtifactsUseCase
import com.udnahc.immichgallery.domain.usecase.detail.GetDetailMosaicCacheUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPeopleUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsPageUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.search.MetadataSearchUseCase
import com.udnahc.immichgallery.domain.usecase.search.SmartSearchUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTimelineGroupSizeUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketGeometryUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineMosaicSectionGeometryUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketsUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineMosaicCacheStatusUseCase
import com.udnahc.immichgallery.ui.screen.album.AlbumDetailViewModel
import com.udnahc.immichgallery.ui.screen.album.AlbumListViewModel
import com.udnahc.immichgallery.ui.navigation.MainScreenViewModel
import com.udnahc.immichgallery.ui.screen.login.LoginViewModel
import com.udnahc.immichgallery.ui.screen.people.PeopleViewModel
import com.udnahc.immichgallery.ui.screen.people.PersonDetailViewModel
import com.udnahc.immichgallery.ui.screen.search.SearchViewModel
import com.udnahc.immichgallery.ui.screen.timeline.TimelineViewModel
import com.udnahc.immichgallery.ui.util.MosaicWorkScheduler
import com.udnahc.immichgallery.ui.util.TimelineMosaicDispatcherProvider
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val sharedModule = module {
    // Settings
    single { Settings() }
    single { ServerConfigRepository(get()) }
    single { MosaicWorkScheduler() }
    factory { TimelineMosaicDispatcherProvider() }

    // Networking
    single { createHttpClientEngine() }
    single { HttpClientFactory.create(get(), get()) }
    single { ImmichApiService(get(), get()) }

    // Repositories
    single { AuthRepository(get()) }
    single { AssetEditsEnricher(get(), get()) }
    single { TimelineRepository(get<AppDatabase>(), get(), get(), get(), get(), get(), get()) }
    single { TimelineMosaicCacheRepository(get(), get(), get()) }
    single { AlbumRepository(get<AppDatabase>(), get(), get(), get(), get(), get(), get()) }
    single { PeopleRepository(get<AppDatabase>(), get(), get(), get(), get(), get(), get()) }
    single { SearchRepository(get()) }
    single { ServerStatusRepository(get()) }
    single { AssetRepository(get(), get(), get(), get()) }
    single { DetailMosaicCacheRepository(get()) }
    single { TimelineMosaicArtifactBuilder() }
    single { TimelineBucketSnapshotReader(get()) }

    // UseCases
    factory { ValidateServerUseCase(get()) }
    factory { GetLoginStatusUseCase(get()) }
    factory { GetApiKeyUseCase(get()) }
    factory { GetServerStatusUseCase(get()) }
    factory { GetTimelineBucketsUseCase(get()) }
    factory { GetBucketAssetsUseCase(get()) }
    factory { GetAssetFileNameUseCase(get()) }
    factory { GetTimelineBucketGeometryUseCase(get()) }
    factory { GetTimelineMosaicSectionGeometryUseCase(get()) }
    factory { GetTimelineMosaicCacheStatusUseCase(get()) }
    factory { GetAssetDetailUseCase(get()) }
    factory { GetAlbumsUseCase(get()) }
    factory { GetAlbumDetailUseCase(get()) }
    factory { GetPeopleUseCase(get()) }
    factory { GetPersonAssetsUseCase(get()) }
    factory { GetPersonAssetsPageUseCase(get()) }
    factory { SmartSearchUseCase(get(), get()) }
    factory { MetadataSearchUseCase(get(), get()) }
    factory { GetTimelineGroupSizeUseCase(get()) }
    factory { GetTargetRowHeightUseCase(get()) }
    factory { GetViewConfigUseCase(get()) }
    factory { GetDetailMosaicArtifactsUseCase(get()) }
    factory { GetDetailMosaicCacheUseCase(get()) }

    // Actions
    factory { SaveServerConfigAction(get()) }
    factory { ClearServerConfigAction(get(), get(), get(), get(), get(), get(), get()) }
    factory { MonitorServerStatusAction(get()) }
    factory { LoadBucketAssetsAction(get()) }
    factory { SyncAllTimelineAssetsAction(get()) }
    factory { PrepareTimelineMosaicCacheAction(get(), get(), get()) }
    factory { ClearTimelineMosaicCacheAction(get()) }
    factory { SetTimelineGroupSizeAction(get()) }
    factory { SetTargetRowHeightAction(get()) }
    factory { SetViewConfigAction(get()) }
    factory { UpsertDetailMosaicArtifactsAction(get()) }
    factory { UpsertDetailMosaicCacheAction(get()) }
    factory { ClearDetailMosaicCacheAction(get()) }

    // ViewModels
    viewModel { LoginViewModel(get(), get()) }
    viewModel { MainScreenViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { TimelineViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { AlbumListViewModel(get()) }
    viewModel { params -> AlbumDetailViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), params.get()) }
    viewModel { PeopleViewModel(get()) }
    viewModel { params -> PersonDetailViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), params.get()) }
    viewModel { SearchViewModel(get(), get(), get(), get(), get(), get()) }
}
