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
import kotlinx.coroutines.flow.first
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
    fun logoutWaitsForSuspendClearAndRetainsCompletionWithoutACollector() =
        runTest(dispatcher.scheduler) {
            val clearGate = CompletableDeferred<Unit>()
            var clearCount = 0
            val viewModel = viewModel(
                ClearServerConfigAction(
                    mutate = { block -> block() },
                    rotateImageCache = { block -> block() },
                    clearServerState = {
                        clearCount++
                        clearGate.await()
                    },
                    clearConfiguration = {}
                )
            )
            viewModel.logout()
            viewModel.logout()
            assertFalse(viewModel.screenState.value.acceptsNavigation)
            runCurrent()

            assertTrue(viewModel.screenState.value.isLoggingOut)
            assertFalse(viewModel.screenState.value.logoutCompleted)

            clearGate.complete(Unit)
            advanceUntilIdle()

            assertFalse(viewModel.screenState.value.isLoggingOut)
            // Completion is still observable when Main starts collecting later.
            assertTrue(viewModel.screenState.first().logoutCompleted)
            assertFalse(viewModel.screenState.value.acceptsNavigation)
            viewModel.logout()
            advanceUntilIdle()
            assertEquals(1, clearCount)
        }

    @Test
    fun logoutFailureKeepsNavigationInPlaceAndPublishesExistingError() =
        runTest(dispatcher.scheduler) {
            val viewModel = viewModel(
                ClearServerConfigAction(
                    mutate = { block -> block() },
                    rotateImageCache = { block -> block() },
                    clearServerState = { error("clear failed") },
                    clearConfiguration = {}
                )
            )
            viewModel.logout()
            advanceUntilIdle()

            assertFalse(viewModel.screenState.value.isLoggingOut)
            assertEquals(MainUiMessage.LogoutFailed, viewModel.screenState.value.message)
            assertFalse(viewModel.screenState.value.logoutCompleted)
            assertTrue(viewModel.screenState.value.acceptsNavigation)
        }

    @Test
    fun failedLogoutCanBeRetriedUntilCompletion() = runTest(dispatcher.scheduler) {
        var clearCount = 0
        val viewModel = viewModel(
            ClearServerConfigAction(
                mutate = { block -> block() },
                rotateImageCache = { block -> block() },
                clearServerState = { if (++clearCount == 1) error("clear failed") },
                clearConfiguration = {}
            )
        )

        viewModel.logout()
        advanceUntilIdle()
        assertEquals(MainUiMessage.LogoutFailed, viewModel.screenState.value.message)
        viewModel.logout()
        advanceUntilIdle()

        assertEquals(2, clearCount)
        assertTrue(viewModel.screenState.value.logoutCompleted)
        assertEquals(null, viewModel.screenState.value.message)
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
