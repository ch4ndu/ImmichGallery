package com.udnahc.immichgallery.ui.navigation

import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.action.auth.EnsureLocalNetworkAccessAction
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessController
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessResult
import com.udnahc.immichgallery.domain.usecase.auth.GetLoginStatusUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetServerUrlUseCase
import com.udnahc.immichgallery.test.TestSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun savedSessionRoutesToMainAfterLocalNetworkGrant() = runTest(dispatcher.scheduler) {
        val configuration = ServerConfigRepository(TestSettings()).apply {
            replaceSession("http://immich.local", "saved-key")
        }
        val requestedOrigins = mutableListOf<String>()
        val viewModel = AppStartupViewModel(
            getLoginStatusUseCase = GetLoginStatusUseCase(configuration),
            getServerUrlUseCase = GetServerUrlUseCase(configuration),
            ensureLocalNetworkAccessAction = EnsureLocalNetworkAccessAction(
                localNetworkController(requestedOrigins, LocalNetworkAccessResult.Granted)
            ),
            workerDispatcher = dispatcher
        )

        advanceUntilIdle()

        assertEquals(listOf("http://immich.local"), requestedOrigins)
        assertEquals(AppStartupDestination.Main, viewModel.state.value.destination)
    }

    @Test
    fun absentSessionRoutesDirectlyToLogin() = runTest(dispatcher.scheduler) {
        val configuration = ServerConfigRepository(TestSettings())
        val requestedOrigins = mutableListOf<String>()
        val viewModel = AppStartupViewModel(
            getLoginStatusUseCase = GetLoginStatusUseCase(configuration),
            getServerUrlUseCase = GetServerUrlUseCase(configuration),
            ensureLocalNetworkAccessAction = EnsureLocalNetworkAccessAction(
                localNetworkController(requestedOrigins, LocalNetworkAccessResult.Granted)
            ),
            workerDispatcher = dispatcher
        )

        advanceUntilIdle()

        assertEquals(emptyList(), requestedOrigins)
        assertEquals(AppStartupDestination.Login, viewModel.state.value.destination)
    }

    private fun localNetworkController(
        requestedOrigins: MutableList<String>,
        result: LocalNetworkAccessResult
    ) = object : LocalNetworkAccessController {
        override suspend fun ensureAccess(serverUrl: String): LocalNetworkAccessResult {
            requestedOrigins += serverUrl
            return result
        }

        override fun openAppSettings() = Unit
    }
}
