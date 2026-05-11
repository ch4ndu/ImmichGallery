package com.udnahc.immichgallery.domain.action.timeline

import com.udnahc.immichgallery.ui.util.SyncActivityKey
import com.udnahc.immichgallery.ui.util.SyncActivityTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface TimelineManualSyncEvent {
    data class Started(val request: TimelineManualSyncRequest) : TimelineManualSyncEvent
    data class Completed(val result: TimelineManualSyncResult) : TimelineManualSyncEvent
    data class Failed(
        val request: TimelineManualSyncRequest,
        val cause: Throwable
    ) : TimelineManualSyncEvent
}

class TimelineManualSyncCoordinator(
    private val action: TimelineManualSyncAction,
    private val syncActivityTracker: SyncActivityTracker
) {
    private val _events = MutableSharedFlow<TimelineManualSyncEvent>(
        extraBufferCapacity = TIMELINE_MANUAL_SYNC_EVENT_BUFFER
    )
    val events: SharedFlow<TimelineManualSyncEvent> = _events.asSharedFlow()

    suspend fun run(request: TimelineManualSyncRequest): Result<TimelineManualSyncResult> {
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
}

private const val TIMELINE_MANUAL_SYNC_EVENT_BUFFER = 16
