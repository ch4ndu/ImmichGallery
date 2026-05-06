package com.udnahc.immichgallery.ui.util

import com.udnahc.immichgallery.ui.screen.timeline.TimelineMosaicWorkSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch

internal data class TimelineMosaicQueueRequest(
    val timeBucket: String,
    val source: TimelineMosaicWorkSource
)

internal sealed interface TimelineMosaicQueueCommand {
    data class Enqueue(
        val requests: List<TimelineMosaicQueueRequest>,
        val visiblePriority: Boolean,
        val visibleOrTargetBuckets: Set<String>
    ) : TimelineMosaicQueueCommand

    data class Defer(
        val requests: List<TimelineMosaicQueueRequest>
    ) : TimelineMosaicQueueCommand

    data object PauseForScroll : TimelineMosaicQueueCommand

    data class ResumeAfterScroll(
        val priorityBuckets: List<String>
    ) : TimelineMosaicQueueCommand

    data class InvalidateBucket(
        val timeBucket: String
    ) : TimelineMosaicQueueCommand

    data object ClearAll : TimelineMosaicQueueCommand

    data class WorkerFinished(
        val request: TimelineMosaicQueueRequest,
        val token: Long
    ) : TimelineMosaicQueueCommand
}

internal sealed interface TimelineMosaicQueueEffect {
    data class StartWork(
        val request: TimelineMosaicQueueRequest,
        val token: Long
    ) : TimelineMosaicQueueEffect

    data class CancelWork(
        val request: TimelineMosaicQueueRequest,
        val token: Long
    ) : TimelineMosaicQueueEffect
}

/**
 * Timeline-only actor for bucket Mosaic queue state. It deliberately owns only
 * scheduling state; TimelineViewModel still performs cache reads, runtime
 * compute, and display publication from the emitted effects.
 */
internal class TimelineMosaicQueueActor(
    scope: CoroutineScope
) {
    private val commands = Channel<TimelineMosaicQueueCommand>(Channel.UNLIMITED)
    private val _effects = Channel<TimelineMosaicQueueEffect>(Channel.UNLIMITED)
    val effects: ReceiveChannel<TimelineMosaicQueueEffect> = _effects

    private val pending = mutableListOf<TimelineMosaicQueueRequest>()
    private val deferred = mutableListOf<TimelineMosaicQueueRequest>()
    private var running: RunningTimelineMosaicRequest? = null
    private var paused = false
    private var nextToken = 1L

    init {
        scope.launch(Dispatchers.Default) {
            for (command in commands) {
                handle(command)
            }
        }
    }

    fun send(command: TimelineMosaicQueueCommand) {
        commands.trySend(command)
    }

    fun close() {
        commands.close()
        _effects.close()
    }

    private suspend fun handle(command: TimelineMosaicQueueCommand) {
        when (command) {
            is TimelineMosaicQueueCommand.Enqueue -> {
                enqueue(command.requests, command.visiblePriority, command.visibleOrTargetBuckets)
            }
            is TimelineMosaicQueueCommand.Defer -> {
                command.requests.forEach(::mergeDeferred)
            }
            TimelineMosaicQueueCommand.PauseForScroll -> {
                paused = true
                cancelRunning(defer = true)
            }
            is TimelineMosaicQueueCommand.ResumeAfterScroll -> {
                paused = false
                val deferredRequests = deferred.toList()
                deferred.clear()
                command.priorityBuckets
                    .distinct()
                    .map { TimelineMosaicQueueRequest(it, TimelineMosaicWorkSource.RenderDemand) }
                    .forEachIndexed { index, request ->
                        mergePending(request, insertIndex = index)
                    }
                deferredRequests.forEach(::mergePending)
                startNextIfEligible()
            }
            is TimelineMosaicQueueCommand.InvalidateBucket -> {
                pending.removeAll { it.timeBucket == command.timeBucket }
                deferred.removeAll { it.timeBucket == command.timeBucket }
                if (running?.request?.timeBucket == command.timeBucket) {
                    cancelRunning(defer = false)
                }
                startNextIfEligible()
            }
            TimelineMosaicQueueCommand.ClearAll -> {
                pending.clear()
                deferred.clear()
                paused = false
                cancelRunning(defer = false)
            }
            is TimelineMosaicQueueCommand.WorkerFinished -> {
                val active = running
                if (active?.request == command.request && active.token == command.token) {
                    running = null
                    startNextIfEligible()
                }
            }
        }
    }

    private suspend fun enqueue(
        requests: List<TimelineMosaicQueueRequest>,
        visiblePriority: Boolean,
        visibleOrTargetBuckets: Set<String>
    ) {
        val uniqueRequests = requests.distinct()
        if (uniqueRequests.isEmpty()) return
        uniqueRequests
            .filter { it.source == TimelineMosaicWorkSource.RenderDemand }
            .firstOrNull { renderRequest ->
                running?.request?.timeBucket == renderRequest.timeBucket &&
                    running?.request?.source == TimelineMosaicWorkSource.SyncPrecompute
            }
            ?.let {
                cancelRunning(defer = false)
            }
        uniqueRequests.forEachIndexed { index, request ->
            val insertIndex = if (visiblePriority) index else null
            mergePending(request, insertIndex)
        }
        val active = running
        val hasVisibleRenderDemand = uniqueRequests.any {
            it.source == TimelineMosaicWorkSource.RenderDemand &&
                it.timeBucket in visibleOrTargetBuckets
        }
        if (visiblePriority &&
            hasVisibleRenderDemand &&
            active != null &&
            active.request.timeBucket !in visibleOrTargetBuckets
        ) {
            cancelRunning(defer = true)
        }
        startNextIfEligible()
    }

    private fun mergePending(
        request: TimelineMosaicQueueRequest,
        insertIndex: Int? = null
    ) {
        if (!shouldAccept(request)) return
        removeSuperseded(request)
        pending.removeAll { it.timeBucket == request.timeBucket && it.source == request.source }
        if (insertIndex != null) {
            pending.add(insertIndex.coerceIn(0, pending.size), request)
        } else {
            pending.add(request)
        }
    }

    private fun mergeDeferred(request: TimelineMosaicQueueRequest) {
        if (!shouldAccept(request)) return
        removeSuperseded(request)
        deferred.removeAll { it.timeBucket == request.timeBucket && it.source == request.source }
        deferred.add(request)
    }

    private fun shouldAccept(request: TimelineMosaicQueueRequest): Boolean {
        if (request.source == TimelineMosaicWorkSource.RenderDemand) return true
        val runningRenderDemand = running?.request?.let {
            it.timeBucket == request.timeBucket && it.source == TimelineMosaicWorkSource.RenderDemand
        } ?: false
        return !runningRenderDemand &&
            pending.none {
                it.timeBucket == request.timeBucket && it.source == TimelineMosaicWorkSource.RenderDemand
            } &&
            deferred.none {
                it.timeBucket == request.timeBucket && it.source == TimelineMosaicWorkSource.RenderDemand
            }
    }

    private fun removeSuperseded(request: TimelineMosaicQueueRequest) {
        if (request.source != TimelineMosaicWorkSource.RenderDemand) return
        pending.removeAll { it.timeBucket == request.timeBucket }
        deferred.removeAll { it.timeBucket == request.timeBucket }
    }

    private suspend fun cancelRunning(defer: Boolean) {
        val active = running ?: return
        running = null
        if (defer) {
            mergeDeferred(active.request)
        }
        _effects.send(TimelineMosaicQueueEffect.CancelWork(active.request, active.token))
    }

    private suspend fun startNextIfEligible() {
        if (paused || running != null || pending.isEmpty()) return
        val request = pending.removeAt(0)
        val token = nextToken++
        running = RunningTimelineMosaicRequest(request, token)
        _effects.send(TimelineMosaicQueueEffect.StartWork(request, token))
    }

    private data class RunningTimelineMosaicRequest(
        val request: TimelineMosaicQueueRequest,
        val token: Long
    )
}
