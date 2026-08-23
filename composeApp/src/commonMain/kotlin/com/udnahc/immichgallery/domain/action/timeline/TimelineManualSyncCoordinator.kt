package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.data.repository.SessionOperationStamp
import com.udnahc.immichgallery.data.repository.SessionOperationRejectedException
import com.udnahc.immichgallery.data.repository.SessionStateRepository
import com.udnahc.immichgallery.data.repository.StaleSessionOperationException
import com.udnahc.immichgallery.domain.action.auth.SessionOperationRunner
import com.udnahc.immichgallery.ui.util.SyncActivityKey
import com.udnahc.immichgallery.ui.util.SyncActivityTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

sealed interface TimelineManualSyncEvent {
    data class Started(val request: TimelineManualSyncRequest) : TimelineManualSyncEvent
    data class Completed(val result: TimelineManualSyncResult) : TimelineManualSyncEvent
    data class Failed(
        val request: TimelineManualSyncRequest,
        val cause: Throwable
    ) : TimelineManualSyncEvent
    data class SessionCancelled(val request: TimelineManualSyncRequest) : TimelineManualSyncEvent
}

@OptIn(ExperimentalAtomicApi::class)
class TimelineManualSyncCoordinator(
    private val action: TimelineManualSyncAction,
    private val syncActivityTracker: SyncActivityTracker,
    private val sessionStateRepository: SessionStateRepository = SessionStateRepository()
) {
    private val requestIds = AtomicLong(0L)
    private val _events = MutableSharedFlow<TimelineManualSyncEvent>(
        extraBufferCapacity = TIMELINE_MANUAL_SYNC_EVENT_BUFFER
    )
    val events: SharedFlow<TimelineManualSyncEvent> = _events.asSharedFlow()

    fun nextRequestId(): Long = requestIds.addAndFetch(1L)

    suspend fun run(request: TimelineManualSyncRequest): Result<TimelineManualSyncResult> {
        return try {
            sessionStateRepository.withOperationForStamp(request.sessionStamp) {
                runRegistered(request)
            }
        } catch (error: StaleSessionOperationException) {
            emitSessionCancelled(request)
            throw error
        } catch (error: SessionOperationRejectedException) {
            emitSessionCancelled(request)
            throw error
        }
    }

    private suspend fun runRegistered(
        request: TimelineManualSyncRequest
    ): Result<TimelineManualSyncResult> {
        _events.emit(TimelineManualSyncEvent.Started(request))
        return try {
            syncActivityTracker.track(SyncActivityKey.TimelineManualSync) {
                action(request)
            }.also { result ->
                result.fold(
                    onSuccess = { _events.emit(TimelineManualSyncEvent.Completed(it)) },
                    onFailure = { _events.emit(TimelineManualSyncEvent.Failed(request, it)) }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _events.emit(TimelineManualSyncEvent.Failed(request, e))
            Result.failure(e)
        }
    }

    private suspend fun emitSessionCancelled(request: TimelineManualSyncRequest) {
        withContext(NonCancellable) {
            _events.emit(TimelineManualSyncEvent.SessionCancelled(request))
        }
    }
}

internal suspend fun TimelineManualSyncEvent.applyIfCurrentToTimelineOwner(
    sessionOperationRunner: SessionOperationRunner,
    ownerEpoch: Long,
    block: suspend () -> Unit
): Boolean {
    val eventStamp = sessionStamp
    if (eventStamp.ownerEpoch != ownerEpoch) return false
    if (this is TimelineManualSyncEvent.SessionCancelled) {
        val admittedStamp = sessionOperationRunner.awaitAdmittedStamp()
        if (admittedStamp.ownerEpoch != ownerEpoch) return false
        block()
        return true
    }
    return coroutineScope {
        val handling = async(start = CoroutineStart.UNDISPATCHED) {
            sessionOperationRunner.runForStamp(eventStamp) {
                block()
            }
        }
        try {
            handling.await()
            true
        } catch (_: StaleSessionOperationException) {
            false
        } catch (_: SessionOperationRejectedException) {
            false
        }
    }
}

internal val TimelineManualSyncEvent.sessionStamp: SessionOperationStamp
    get() = when (this) {
        is TimelineManualSyncEvent.Started -> request.sessionStamp
        is TimelineManualSyncEvent.Completed -> result.request.sessionStamp
        is TimelineManualSyncEvent.Failed -> request.sessionStamp
        is TimelineManualSyncEvent.SessionCancelled -> request.sessionStamp
    }

internal fun TimelineManualSyncEvent.matchesActiveRequest(
    activeRequestId: Long?
): Boolean = activeRequestId != null && requestId == activeRequestId

internal val TimelineManualSyncEvent.requestId: Long
    get() = when (this) {
        is TimelineManualSyncEvent.Started -> request.requestId
        is TimelineManualSyncEvent.Completed -> result.request.requestId
        is TimelineManualSyncEvent.Failed -> request.requestId
        is TimelineManualSyncEvent.SessionCancelled -> request.requestId
    }

private const val TIMELINE_MANUAL_SYNC_EVENT_BUFFER = 16
