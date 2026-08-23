package com.udnahc.immichgallery.ui.navigation

import com.udnahc.immichgallery.data.remote.HttpClientFactory
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.data.repository.ServerStatusRepository
import com.udnahc.immichgallery.domain.action.auth.ClearServerConfigAction
import com.udnahc.immichgallery.domain.action.auth.MonitorServerStatusAction
import com.udnahc.immichgallery.domain.action.auth.StopServerStatusMonitoringAction
import com.udnahc.immichgallery.domain.action.settings.SetTimelineGroupSizeAction
import com.udnahc.immichgallery.domain.action.settings.SetViewConfigAction
import com.udnahc.immichgallery.domain.usecase.auth.GetServerStatusUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetTimelineGroupSizeUseCase
import com.udnahc.immichgallery.domain.usecase.settings.GetViewConfigUseCase
import com.udnahc.immichgallery.test.TestSettings
import com.udnahc.immichgallery.ui.model.MainUiMessage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun logoutWaitsForSuspendClearAndEmitsCompletionOnlyAfterSuccess() =
        runTest(dispatcher.scheduler) {
            val clearGate = CompletableDeferred<Unit>()
            val events = mutableListOf<Unit>()
            val viewModel = viewModel(
                ClearServerConfigAction(
                    mutate = { block -> block() },
                    rotateImageCache = { block -> block() },
                    clearServerState = { clearGate.await() },
                    clearConfiguration = {}
                )
            )
            backgroundScope.launch { viewModel.logoutCompleted.collect { events += it } }

            viewModel.logout()
            runCurrent()

            assertTrue(viewModel.screenState.value.isLoggingOut)
            assertEquals(emptyList(), events)

            clearGate.complete(Unit)
            advanceUntilIdle()

            assertFalse(viewModel.screenState.value.isLoggingOut)
            assertEquals(listOf(Unit), events)
        }

    @Test
    fun logoutFailureKeepsNavigationInPlaceAndPublishesExistingError() =
        runTest(dispatcher.scheduler) {
            val events = mutableListOf<Unit>()
            val viewModel = viewModel(
                ClearServerConfigAction(
                    mutate = { block -> block() },
                    rotateImageCache = { block -> block() },
                    clearServerState = { error("clear failed") },
                    clearConfiguration = {}
                )
            )
            backgroundScope.launch { viewModel.logoutCompleted.collect { events += it } }

            viewModel.logout()
            advanceUntilIdle()

            assertFalse(viewModel.screenState.value.isLoggingOut)
            assertEquals(MainUiMessage.LogoutFailed, viewModel.screenState.value.message)
            assertEquals(emptyList(), events)
        }

    private fun viewModel(clearAction: ClearServerConfigAction): MainScreenViewModel {
        val configuration = ServerConfigRepository(TestSettings())
        val client = HttpClientFactory.create(MockEngine { respond("", HttpStatusCode.OK) })
        val statusRepository = ServerStatusRepository(ImmichApiService(client, configuration))
        return MainScreenViewModel(
            getServerStatusUseCase = GetServerStatusUseCase(statusRepository),
            monitorServerStatusAction = MonitorServerStatusAction(statusRepository),
            stopServerStatusMonitoringAction = StopServerStatusMonitoringAction(statusRepository),
            getTimelineGroupSizeUseCase = GetTimelineGroupSizeUseCase(configuration),
            setTimelineGroupSizeAction = SetTimelineGroupSizeAction(configuration),
            getViewConfigUseCase = GetViewConfigUseCase(configuration),
            setViewConfigAction = SetViewConfigAction(configuration),
            clearServerConfigAction = clearAction,
            workerDispatcher = dispatcher
        )
    }
}
