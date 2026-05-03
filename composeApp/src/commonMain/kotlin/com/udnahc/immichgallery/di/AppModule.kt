package com.udnahc.immichgallery.di

import com.russhwolf.settings.Settings
import com.udnahc.immichgallery.data.remote.HttpClientFactory
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.data.remote.createHttpClientEngine
import com.udnahc.immichgallery.data.repository.AlbumRepository
import com.udnahc.immichgallery.data.repository.AssetEditsEnricher
import com.udnahc.immichgallery.data.repository.AssetRepository
import com.udnahc.immichgallery.data.repository.AuthRepository
import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.data.repository.SearchRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.data.repository.ServerStatusRepository
import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.action.auth.ClearServerConfigAction
import com.udnahc.immichgallery.domain.action.auth.MonitorServerStatusAction
import com.udnahc.immichgallery.domain.action.auth.SaveServerConfigAction
import com.udnahc.immichgallery.domain.action.settings.SetTargetRowHeightAction
import com.udnahc.immichgallery.domain.action.settings.SetTimelineGroupSizeAction
import com.udnahc.immichgallery.domain.action.timeline.LoadBucketAssetsAction
import com.udnahc.immichgallery.domain.usecase.timeline.GetBucketAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumDetailUseCase
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumsUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetLoginStatusUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetServerStatusUseCase
import com.udnahc.immichgallery.domain.usecase.auth.ValidateServerUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPeopleUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsPageUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.search.MetadataSearchUseCase
import com.udnahc.immichgallery.domain.usecase.search.SmartSearchUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTargetRowHeightUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTimelineGroupSizeUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketsUseCase
import com.udnahc.immichgallery.ui.screen.album.AlbumDetailViewModel
import com.udnahc.immichgallery.ui.screen.album.AlbumListViewModel
import com.udnahc.immichgallery.ui.navigation.MainScreenViewModel
import com.udnahc.immichgallery.ui.screen.login.LoginViewModel
import com.udnahc.immichgallery.ui.screen.people.PeopleViewModel
import com.udnahc.immichgallery.ui.screen.people.PersonDetailViewModel
import com.udnahc.immichgallery.ui.screen.search.SearchViewModel
import com.udnahc.immichgallery.ui.screen.timeline.TimelineViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val sharedModule = module {
    // Settings
    single { Settings() }
    single { ServerConfigRepository(get()) }

    // Networking
    single { createHttpClientEngine() }
    single { HttpClientFactory.create(get(), get()) }
    single { ImmichApiService(get(), get()) }

    // Repositories
    single { AuthRepository(get()) }
    single { AssetEditsEnricher(get(), get()) }
    single { TimelineRepository(get(), get(), get(), get(), get()) }
    single { AlbumRepository(get(), get(), get(), get(), get(), get()) }
    single { PeopleRepository(get(), get(), get(), get(), get(), get()) }
    single { SearchRepository(get()) }
    single { ServerStatusRepository(get()) }
    single { AssetRepository(get(), get(), get(), get()) }

    // UseCases
    factory { ValidateServerUseCase(get()) }
    factory { GetLoginStatusUseCase(get()) }
    factory { GetApiKeyUseCase(get()) }
    factory { GetServerStatusUseCase(get()) }
    factory { GetTimelineBucketsUseCase(get()) }
    factory { GetBucketAssetsUseCase(get()) }
    factory { GetAssetFileNameUseCase(get()) }
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

    // Actions
    factory { SaveServerConfigAction(get()) }
    factory { ClearServerConfigAction(get(), get(), get(), get(), get()) }
    factory { MonitorServerStatusAction(get()) }
    factory { LoadBucketAssetsAction(get()) }
    factory { SetTimelineGroupSizeAction(get()) }
    factory { SetTargetRowHeightAction(get()) }

    // ViewModels
    viewModel { LoginViewModel(get(), get()) }
    viewModel { MainScreenViewModel(get(), get()) }
    viewModel { TimelineViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { AlbumListViewModel(get()) }
    viewModel { params -> AlbumDetailViewModel(get(), get(), get(), params.get()) }
    viewModel { PeopleViewModel(get()) }
    viewModel { params -> PersonDetailViewModel(get(), get(), get(), get(), params.get()) }
    viewModel { SearchViewModel(get(), get(), get(), get()) }
}
