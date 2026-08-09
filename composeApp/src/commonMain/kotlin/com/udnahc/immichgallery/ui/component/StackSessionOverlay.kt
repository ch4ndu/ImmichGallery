package com.udnahc.immichgallery.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.udnahc.immichgallery.domain.model.Asset
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetStack
import com.udnahc.immichgallery.domain.model.AssetStackAccessException
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.ui.model.StackUiMessage
import com.udnahc.immichgallery.ui.model.asText
import com.udnahc.immichgallery.ui.theme.Dimens
import com.udnahc.immichgallery.ui.util.PlatformBackHandler
import immichgallery.composeapp.generated.resources.Res
import immichgallery.composeapp.generated.resources.back
import immichgallery.composeapp.generated.resources.loading_stack
import immichgallery.composeapp.generated.resources.retry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Immutable
internal sealed interface StackAvailability {
    data object Unknown : StackAvailability
    data class Checking(val requestId: Long) : StackAvailability
    data class Available(val stack: AssetStack) : StackAvailability
    data object NoStack : StackAvailability
    data class Failed(val message: StackUiMessage) : StackAvailability
}

internal enum class StackRequestKind { PREFLIGHT, SESSION }

@Immutable
internal data class StackRequest(
    val id: Long,
    val assetId: String,
    val kind: StackRequestKind
)

@Immutable
internal sealed interface StackSession {
    val originAssetId: String

    data class Loading(override val originAssetId: String) : StackSession

    data class Ready(
        override val originAssetId: String,
        val stack: AssetStack
    ) : StackSession

    data class Failed(
        override val originAssetId: String,
        val message: StackUiMessage
    ) : StackSession
}

internal data class StackBrowserState(
    val availabilityByAssetId: Map<String, StackAvailability> = emptyMap(),
    val activeRequest: StackRequest? = null,
    val session: StackSession? = null,
    val nextRequestId: Long = 1L
) {
    fun availability(assetId: String): StackAvailability =
        availabilityByAssetId[assetId] ?: StackAvailability.Unknown

    fun beginPreflight(assetId: String): StackBrowserState {
        if (availability(assetId) !is StackAvailability.Unknown) return this
        return beginRequest(assetId, StackRequestKind.PREFLIGHT)
    }

    fun retryPreflight(assetId: String): StackBrowserState {
        if (availability(assetId) !is StackAvailability.Failed) return this
        return beginRequest(assetId, StackRequestKind.PREFLIGHT)
    }

    fun beginSession(assetId: String): StackBrowserState {
        val available = availability(assetId) as? StackAvailability.Available
        return if (available != null) {
            copy(
                activeRequest = null,
                session = StackSession.Ready(assetId, available.stack)
            )
        } else {
            beginRequest(assetId, StackRequestKind.SESSION)
                .copy(session = StackSession.Loading(assetId))
        }
    }

    fun retrySession(): StackBrowserState {
        val failed = session as? StackSession.Failed ?: return this
        return beginRequest(failed.originAssetId, StackRequestKind.SESSION)
            .copy(session = StackSession.Loading(failed.originAssetId))
    }

    fun complete(
        request: StackRequest,
        result: Result<AssetStackLookup>
    ): StackBrowserState {
        if (activeRequest != request) return this
        val lookup = result.getOrNull()
        val failureMessage = result.exceptionOrNull()?.toStackUiMessage()
        return when (request.kind) {
            StackRequestKind.PREFLIGHT -> {
                val availability = when {
                    failureMessage != null -> StackAvailability.Failed(failureMessage)
                    lookup is AssetStackLookup.Available -> StackAvailability.Available(lookup.stack)
                    lookup is AssetStackLookup.NoStack -> StackAvailability.NoStack
                    else -> StackAvailability.Failed(StackUiMessage.NoSupportedMembers)
                }
                copy(
                    availabilityByAssetId = availabilityByAssetId + (request.assetId to availability),
                    activeRequest = null
                )
            }

            StackRequestKind.SESSION -> {
                val stackSession = when {
                    failureMessage != null -> StackSession.Failed(request.assetId, failureMessage)
                    lookup is AssetStackLookup.Available -> StackSession.Ready(request.assetId, lookup.stack)
                    lookup is AssetStackLookup.NoStack ->
                        StackSession.Failed(request.assetId, StackUiMessage.NoLongerStacked)
                    else -> StackSession.Failed(request.assetId, StackUiMessage.NoSupportedMembers)
                }
                val availability = when (stackSession) {
                    is StackSession.Ready -> StackAvailability.Available(stackSession.stack)
                    is StackSession.Failed -> StackAvailability.Failed(stackSession.message)
                    is StackSession.Loading -> StackAvailability.Checking(request.id)
                }
                copy(
                    availabilityByAssetId = availabilityByAssetId + (request.assetId to availability),
                    activeRequest = null,
                    session = stackSession
                )
            }
        }
    }

    fun settledAssetChanged(assetId: String?): StackBrowserState {
        val request = activeRequest ?: return this
        if (request.assetId == assetId || session != null) return this
        return invalidateActiveRequest()
    }

    fun invalidateActiveRequest(): StackBrowserState {
        val request = activeRequest ?: return this
        val availability = availability(request.assetId)
        val restored = if (availability is StackAvailability.Checking) {
            StackAvailability.Unknown
        } else {
            availability
        }
        return copy(
            availabilityByAssetId = availabilityByAssetId + (request.assetId to restored),
            activeRequest = null
        )
    }

    fun dismissStackSession(): StackBrowserState = if (session == null) {
        this
    } else {
        copy(
            activeRequest = activeRequest?.takeUnless { it.kind == StackRequestKind.SESSION },
            session = null
        )
    }

    private fun beginRequest(
        assetId: String,
        kind: StackRequestKind
    ): StackBrowserState {
        val request = StackRequest(nextRequestId, assetId, kind)
        val availability = if (kind == StackRequestKind.PREFLIGHT) {
            availabilityByAssetId + (assetId to StackAvailability.Checking(request.id))
        } else {
            availabilityByAssetId
        }
        return copy(
            availabilityByAssetId = availability,
            activeRequest = request,
            nextRequestId = nextRequestId + 1L
        )
    }
}

internal fun Throwable.toStackUiMessage(): StackUiMessage = when (this) {
    is AssetStackAccessException.PermissionDenied -> StackUiMessage.PermissionDenied
    is AssetStackAccessException.Unavailable -> StackUiMessage.Unavailable
    else -> StackUiMessage.RequestFailed
}

internal class StackSessionController(
    private val scope: CoroutineScope,
    private var loadAssetStack: suspend (String) -> Result<AssetStackLookup>
) {
    var state by mutableStateOf(StackBrowserState())
        private set

    private var requestJob: Job? = null

    fun updateLoader(loader: suspend (String) -> Result<AssetStackLookup>) {
        loadAssetStack = loader
    }

    fun onSettledAssetChanged(assetId: String?) {
        val updated = state.settledAssetChanged(assetId)
        if (updated.activeRequest != state.activeRequest) requestJob?.cancel()
        state = updated
    }

    fun preflight(asset: Asset) {
        if (asset.stackCount > 0) return
        launchIfStarted(state.beginPreflight(asset.id))
    }

    fun retryPreflight(assetId: String) {
        launchIfStarted(state.retryPreflight(assetId))
    }

    fun openSession(assetId: String) {
        launchIfStarted(state.beginSession(assetId))
    }

    fun retrySession() {
        launchIfStarted(state.retrySession())
    }

    fun dismissSession() {
        requestJob?.cancel()
        requestJob = null
        state = state.dismissStackSession()
    }

    fun close() {
        requestJob?.cancel()
        requestJob = null
        state = state.invalidateActiveRequest()
    }

    private fun launchIfStarted(updated: StackBrowserState) {
        val previousRequest = state.activeRequest
        val request = updated.activeRequest
        state = updated
        if (request == null || request == previousRequest) return
        requestJob?.cancel()
        requestJob = scope.launch {
            val result = loadAssetStack(request.assetId)
            state = state.complete(request, result)
        }
    }
}

@Composable
internal fun rememberStackSessionController(
    getAssetStack: suspend (String) -> Result<AssetStackLookup>
): StackSessionController {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { StackSessionController(scope, getAssetStack) }
    controller.updateLoader(getAssetStack)
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    return controller
}

@Composable
internal fun StackSessionOverlay(
    session: StackSession,
    apiKey: String,
    getAssetDetail: suspend (String) -> Result<AssetDetail>,
    getAssetStack: suspend (String) -> Result<AssetStackLookup>,
    onPersonClick: (personId: String, personName: String) -> Unit,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    when (session) {
        is StackSession.Loading -> StackSessionStatus(
            message = stringResource(Res.string.loading_stack),
            loading = true,
            onBack = onDismiss
        )

        is StackSession.Failed -> StackSessionStatus(
            message = session.message.asText(),
            loading = false,
            onBack = onDismiss,
            onRetry = onRetry
        )

        is StackSession.Ready -> StaticPhotoOverlay(
            assets = session.stack.assets,
            initialIndex = session.stack.initialIndex(session.originAssetId),
            apiKey = apiKey,
            getAssetDetail = getAssetDetail,
            getAssetStack = getAssetStack,
            stackBrowsingEnabled = false,
            onPersonClick = onPersonClick,
            onDismiss = { onDismiss() }
        )
    }
}

@Composable
private fun StackSessionStatus(
    message: String,
    loading: Boolean,
    onBack: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    PlatformBackHandler(enabled = true, onBack = onBack)
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(Dimens.largeSpacing),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
        ) {
            if (loading) CircularProgressIndicator(color = Color.White)
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge
            )
            if (onRetry != null) {
                Button(onClick = onRetry) {
                    Text(stringResource(Res.string.retry))
                }
            }
            Button(onClick = onBack) {
                Text(stringResource(Res.string.back))
            }
        }
    }
}
