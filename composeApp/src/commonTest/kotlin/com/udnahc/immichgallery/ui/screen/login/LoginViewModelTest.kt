package com.udnahc.immichgallery.ui.screen.login

import com.udnahc.immichgallery.data.remote.HttpClientFactory
import com.udnahc.immichgallery.data.remote.ImmichApiService
import com.udnahc.immichgallery.data.repository.AuthRepository
import com.udnahc.immichgallery.data.repository.ServerConfigRepository
import com.udnahc.immichgallery.domain.action.auth.EnsureLocalNetworkAccessAction
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessController
import com.udnahc.immichgallery.domain.action.auth.LocalNetworkAccessResult
import com.udnahc.immichgallery.domain.action.auth.OpenLocalNetworkSettingsAction
import com.udnahc.immichgallery.domain.action.auth.ReplaceServerSessionAction
import com.udnahc.immichgallery.domain.action.auth.ServerConfiguration
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetServerUrlUseCase
import com.udnahc.immichgallery.domain.usecase.auth.ValidateServerUseCase
import com.udnahc.immichgallery.test.TestSettings
import com.udnahc.immichgallery.ui.model.LoginUiMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clients = mutableListOf<HttpClient>()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        clients.forEach(HttpClient::close)
        Dispatchers.resetMain()
    }

    @Test
    fun submissionLocksTheCapturedPairBeforeDispatchAndThroughoutValidation() = runTest(dispatcher.scheduler) {
        val validation = CompletableDeferred<Unit>()
        val submitted = mutableListOf<ServerConfiguration>()
        val fixture = fixture(validation, submitted)
        val viewModel = fixture.viewModel
        viewModel.updateServerUrl("https://submitted.example/")
        viewModel.updateApiKey("submitted-key")

        viewModel.login()
        viewModel.updateServerUrl("https://other.example")
        viewModel.updateApiKey("other-key")
        viewModel.login()
        assertTrue(viewModel.state.value.isLoading)
        assertEquals("https://submitted.example/", viewModel.state.value.serverUrl)
        assertEquals("submitted-key", viewModel.state.value.apiKey)

        runCurrent()
        assertEquals(1, submitted.size)
        viewModel.updateApiKey("later-key")
        assertEquals("submitted-key", viewModel.state.value.apiKey)
        assertEquals("", fixture.configuration.getServerUrl())
        validation.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(ServerConfiguration("https://submitted.example", "submitted-key")), submitted)
        assertEquals("https://submitted.example", fixture.configuration.getServerUrl())
        assertEquals("submitted-key", fixture.configuration.getApiKey())
        assertTrue(viewModel.state.value.isLoggedIn)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun failedSubmissionUnlocksEditingAndNewRetryUsesNewPair() = runTest(dispatcher.scheduler) {
        val validation = CompletableDeferred<Unit>()
        val submitted = mutableListOf<ServerConfiguration>()
        val fixture = fixture(validation, submitted, failFirst = true)
        val viewModel = fixture.viewModel
        viewModel.updateServerUrl("https://first.example")
        viewModel.updateApiKey("first-key")
        viewModel.login()
        runCurrent()
        assertEquals(1, submitted.size)
        validation.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.isLoggedIn)
        assertEquals(LoginUiMessage.ConnectionFailed, viewModel.state.value.error)
        assertEquals("", fixture.configuration.getServerUrl())

        viewModel.updateServerUrl("https://second.example")
        viewModel.updateApiKey("second-key")
        assertEquals(null, viewModel.state.value.error)
        viewModel.login()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isLoggedIn)
        assertEquals("https://second.example", fixture.configuration.getServerUrl())
        assertEquals("second-key", fixture.configuration.getApiKey())
        assertEquals(2, submitted.size)
    }

    private fun fixture(
        validation: CompletableDeferred<Unit>,
        submitted: MutableList<ServerConfiguration>,
        failFirst: Boolean = false
    ): Fixture {
        val configuration = ServerConfigRepository(TestSettings())
        val client = HttpClientFactory.create(MockEngine.create {
            // Engine work must share virtual time with the ViewModel; otherwise
            // advanceUntilIdle can return while the mock is still on its IO pool.
            dispatcher = this@LoginViewModelTest.dispatcher
            addHandler { request ->
                if (request.url.encodedPath == "/api/server/ping") {
                    respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    submitted += ServerConfiguration(
                        "${request.url.protocol.name}://${request.url.host}",
                        request.headers["x-api-key"].orEmpty()
                    )
                    validation.await()
                    respond(
                        "[]",
                        status = if (failFirst && submitted.size == 1) HttpStatusCode.Unauthorized else HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }).also(clients::add)
        val controller = object : LocalNetworkAccessController {
            override suspend fun ensureAccess(serverUrl: String) = LocalNetworkAccessResult.NotRequired
            override fun openAppSettings() = Unit
        }
        return Fixture(
            LoginViewModel(
                validateServerUseCase = ValidateServerUseCase(AuthRepository(ImmichApiService(client, configuration))),
                replaceServerSessionAction = ReplaceServerSessionAction(
                    currentConfiguration = {
                        ServerConfiguration(configuration.getServerUrl(), configuration.getApiKey())
                    },
                    mutate = { it() },
                    rotateImageCache = { it() },
                    clearServerState = {},
                    replaceConfiguration = { configuration.replaceSession(it.serverUrl, it.apiKey) }
                ),
                getServerUrlUseCase = GetServerUrlUseCase(configuration),
                getApiKeyUseCase = GetApiKeyUseCase(configuration),
                ensureLocalNetworkAccessAction = EnsureLocalNetworkAccessAction(controller),
                openLocalNetworkSettingsAction = OpenLocalNetworkSettingsAction(controller),
                workerDispatcher = dispatcher
            ),
            configuration
        )
    }

    private data class Fixture(val viewModel: LoginViewModel, val configuration: ServerConfigRepository)
}
