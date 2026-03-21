package com.udnahc.immichgallery.di

import com.russhwolf.settings.Settings
import com.udnahc.immichgallery.data.local.AppDatabase
import com.udnahc.immichgallery.data.remote.HttpClientFactory
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.data.remote.createHttpClientEngine
import com.udnahc.immichgallery.data.repository.AlbumRepository
import com.udnahc.immichgallery.data.repository.AuthRepository
import com.udnahc.immichgallery.data.repository.PeopleRepository
import com.udnahc.immichgallery.data.repository.SearchRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.data.repository.TimelineRepository
import com.udnahc.immichgallery.domain.action.auth.ClearServerConfigAction
import com.udnahc.immichgallery.domain.action.auth.SaveServerConfigAction
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumDetailUseCase
import com.udnahc.immichgallery.domain.usecase.album.GetAlbumsUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetLoginStatusUseCase
import com.udnahc.immichgallery.domain.usecase.auth.ValidateServerUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPeopleUseCase
import com.udnahc.immichgallery.domain.usecase.people.GetPersonAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.search.MetadataSearchUseCase
import com.udnahc.immichgallery.domain.usecase.search.SmartSearchUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetAssetFileNameUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetBucketAssetsUseCase
import com.udnahc.immichgallery.domain.usecase.timeline.GetTimelineBucketsUseCase
import com.udnahc.immichgallery.ui.screen.album.AlbumDetailViewModel
import com.udnahc.immichgallery.ui.screen.album.AlbumListViewModel
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

    // DAOs (from platform-provided AppDatabase)
    single { get<AppDatabase>().timelineAssetDao() }
    single { get<AppDatabase>().albumAssetDao() }
    single { get<AppDatabase>().personAssetDao() }

    // Repositories
    single { AuthRepository(get()) }
    single { TimelineRepository(get(), get()) }
    single { AlbumRepository(get(), get()) }
    single { PeopleRepository(get(), get()) }
    single { SearchRepository(get()) }

    // UseCases
    factory { ValidateServerUseCase(get()) }
    factory { GetLoginStatusUseCase(get()) }
    factory { GetApiKeyUseCase(get()) }
    factory { GetTimelineBucketsUseCase(get()) }
    factory { GetBucketAssetsUseCase(get(), get()) }
    factory { GetAssetFileNameUseCase(get()) }
    factory { GetAssetDetailUseCase(get(), get()) }
    factory { GetAlbumsUseCase(get(), get()) }
    factory { GetAlbumDetailUseCase(get(), get()) }
    factory { GetPeopleUseCase(get(), get()) }
    factory { GetPersonAssetsUseCase(get(), get()) }
    factory { SmartSearchUseCase(get(), get()) }
    factory { MetadataSearchUseCase(get(), get()) }

    // Actions
    factory { SaveServerConfigAction(get()) }
    factory { ClearServerConfigAction(get()) }

    // ViewModels
    viewModel { LoginViewModel(get(), get()) }
    viewModel { TimelineViewModel(get(), get(), get()) }
    viewModel { AlbumListViewModel(get()) }
    viewModel { params -> AlbumDetailViewModel(get(), get(), params.get()) }
    viewModel { PeopleViewModel(get()) }
    viewModel { params -> PersonDetailViewModel(get(), get(), params.get()) }
    viewModel { SearchViewModel(get(), get(), get()) }
}
