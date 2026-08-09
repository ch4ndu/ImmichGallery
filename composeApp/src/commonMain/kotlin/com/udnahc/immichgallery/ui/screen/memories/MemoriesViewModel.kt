package com.udnahc.immichgallery.ui.screen.memories

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.udnahc.immichgallery.domain.model.AssetDetail
import com.udnahc.immichgallery.domain.model.AssetStackLookup
import com.udnahc.immichgallery.domain.model.Memory
import com.udnahc.immichgallery.domain.model.MemoryAccessException
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetDetailUseCase
import com.udnahc.immichgallery.domain.usecase.asset.GetAssetStackUseCase
import com.udnahc.immichgallery.domain.usecase.auth.GetApiKeyUseCase
import com.udnahc.immichgallery.domain.usecase.memory.GetMemoriesForDateUseCase
import com.udnahc.immichgallery.ui.model.MemoriesUiMessage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

@Immutable
data class MemoriesState(
    val memories: List<Memory> = emptyList(),
    val isLoading: Boolean = true,
    val error: MemoriesUiMessage? = null,
    val requestedDate: LocalDate? = null,
    val selectedMemoryId: String? = null,
    internal val activeRequestGeneration: Long = 0L
)

class MemoriesViewModel internal constructor(
    private val loadMemories: suspend (LocalDate) -> Result<List<Memory>>,
    val apiKey: String,
    private val loadAssetDetail: suspend (String) -> Result<AssetDetail>,
    private val loadAssetStack: suspend (String) -> Result<AssetStackLookup>,
    private val now: () -> Instant,
    private val systemTimeZone: () -> TimeZone,
    private val requestDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    constructor(
        getMemoriesForDateUseCase: GetMemoriesForDateUseCase,
        getApiKeyUseCase: GetApiKeyUseCase,
        getAssetDetailUseCase: GetAssetDetailUseCase,
        getAssetStackUseCase: GetAssetStackUseCase
    ) : this(
        loadMemories = getMemoriesForDateUseCase::invoke,
        apiKey = getApiKeyUseCase(),
        loadAssetDetail = getAssetDetailUseCase::invoke,
        loadAssetStack = getAssetStackUseCase::invoke,
        now = Clock.System::now,
        systemTimeZone = { TimeZone.currentSystemDefault() }
    )

    private val _state = MutableStateFlow(MemoriesState())
    val state: StateFlow<MemoriesState> = _state.asStateFlow()

    private var requestGeneration = 0L
    private var loadJob: Job? = null

    init {
        retry()
    }

    fun retry() {
        loadJob?.cancel()
        requestGeneration++
        val generation = requestGeneration
        val requestedDate = now().toLocalDateTime(systemTimeZone()).date
        _state.update {
            it.copy(
                memories = emptyList(),
                isLoading = true,
                error = null,
                requestedDate = requestedDate,
                selectedMemoryId = null,
                activeRequestGeneration = generation
            )
        }
        loadJob = viewModelScope.launch(requestDispatcher) {
            loadMemories(requestedDate).fold(
                onSuccess = { memories ->
                    updateForGeneration(generation) {
                        it.copy(
                            memories = memories,
                            isLoading = false,
                            error = null
                        )
                    }
                },
                onFailure = { error ->
                    updateForGeneration(generation) {
                        it.copy(
                            isLoading = false,
                            error = error.toMemoriesUiMessage()
                        )
                    }
                }
            )
        }
    }

    fun selectMemory(memoryId: String) {
        _state.update { current ->
            if (current.memories.none { it.id == memoryId }) current
            else current.copy(selectedMemoryId = memoryId)
        }
    }

    suspend fun getAssetDetail(assetId: String): Result<AssetDetail> =
        loadAssetDetail(assetId)

    suspend fun getAssetStack(assetId: String): Result<AssetStackLookup> =
        loadAssetStack(assetId)

    private fun updateForGeneration(
        generation: Long,
        transform: (MemoriesState) -> MemoriesState
    ): Boolean {
        while (true) {
            val current = _state.value
            if (current.activeRequestGeneration != generation) return false
            if (_state.compareAndSet(current, transform(current))) return true
        }
    }
}

internal fun Throwable.toMemoriesUiMessage(): MemoriesUiMessage = when (this) {
    is MemoryAccessException.PermissionDenied -> MemoriesUiMessage.PermissionDenied
    is MemoryAccessException.Unavailable -> MemoriesUiMessage.Unavailable
    else -> MemoriesUiMessage.LoadFailed
}
