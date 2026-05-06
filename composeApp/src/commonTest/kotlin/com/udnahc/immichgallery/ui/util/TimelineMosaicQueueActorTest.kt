package com.udnahc.immichgallery.ui.util

import com.udnahc.immichgallery.ui.screen.timeline.TimelineMosaicWorkSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineMosaicQueueActorTest {
    @Test
    fun renderDemandSupersedesSyncPrecomputeForSameBucket() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(enqueue(listOf(sync("bucket"))))
        runCurrent()
        val syncStart = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork

        actor.send(enqueue(listOf(render("bucket")), visiblePriority = true, visible = setOf("bucket")))
        runCurrent()

        assertEquals(
            TimelineMosaicQueueEffect.CancelWork(sync("bucket"), syncStart.token),
            actor.effects.receive()
        )
        assertEquals(render("bucket"), (actor.effects.receive() as TimelineMosaicQueueEffect.StartWork).request)
        closeActor(actor)
    }

    @Test
    fun syncPrecomputeDoesNotReplaceExistingRenderDemand() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(enqueue(listOf(render("bucket"))))
        runCurrent()
        val start = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork

        actor.send(enqueue(listOf(sync("bucket"))))
        actor.send(TimelineMosaicQueueCommand.WorkerFinished(start.request, start.token))
        runCurrent()

        assertEquals(null, actor.effects.tryReceive().getOrNull())
        closeActor(actor)
    }

    @Test
    fun visibleRenderDemandPreemptsOnlyOffscreenRunningWork() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(enqueue(listOf(render("offscreen"))))
        runCurrent()
        val offscreenStart = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork

        actor.send(enqueue(listOf(render("visible")), visiblePriority = true, visible = setOf("visible")))
        runCurrent()

        assertEquals(
            TimelineMosaicQueueEffect.CancelWork(render("offscreen"), offscreenStart.token),
            actor.effects.receive()
        )
        assertEquals(render("visible"), (actor.effects.receive() as TimelineMosaicQueueEffect.StartWork).request)
        closeActor(actor)
    }

    @Test
    fun visibleRenderDemandDoesNotPreemptVisibleRunningWork() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(enqueue(listOf(render("visible_a")), visiblePriority = true, visible = setOf("visible_a")))
        runCurrent()
        val firstStart = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork

        actor.send(
            enqueue(
                listOf(render("visible_b")),
                visiblePriority = true,
                visible = setOf("visible_a", "visible_b")
            )
        )
        runCurrent()

        assertEquals(null, actor.effects.tryReceive().getOrNull())
        actor.send(TimelineMosaicQueueCommand.WorkerFinished(firstStart.request, firstStart.token))
        runCurrent()
        assertEquals(render("visible_b"), (actor.effects.receive() as TimelineMosaicQueueEffect.StartWork).request)
        closeActor(actor)
    }

    @Test
    fun pauseDefersRunningWorkAndEmitsOneCancel() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(enqueue(listOf(render("bucket"))))
        runCurrent()
        val start = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork

        actor.send(TimelineMosaicQueueCommand.PauseForScroll)
        runCurrent()

        assertEquals(
            TimelineMosaicQueueEffect.CancelWork(render("bucket"), start.token),
            actor.effects.receive()
        )
        assertEquals(null, actor.effects.tryReceive().getOrNull())
        closeActor(actor)
    }

    @Test
    fun pauseAndCancellationDeferDoesNotDuplicateWork() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(enqueue(listOf(render("bucket"))))
        runCurrent()
        val start = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork
        actor.send(TimelineMosaicQueueCommand.PauseForScroll)
        runCurrent()
        actor.effects.receive()

        actor.send(TimelineMosaicQueueCommand.Defer(listOf(render("bucket"))))
        actor.send(TimelineMosaicQueueCommand.WorkerFinished(start.request, start.token))
        actor.send(TimelineMosaicQueueCommand.ResumeAfterScroll(emptyList()))
        runCurrent()

        assertEquals(render("bucket"), (actor.effects.receive() as TimelineMosaicQueueEffect.StartWork).request)
        assertEquals(null, actor.effects.tryReceive().getOrNull())
        closeActor(actor)
    }

    @Test
    fun resumeStartsPriorityBucketsBeforeDeferredOffscreenWork() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(TimelineMosaicQueueCommand.PauseForScroll)
        actor.send(TimelineMosaicQueueCommand.Defer(listOf(render("offscreen"))))
        actor.send(TimelineMosaicQueueCommand.ResumeAfterScroll(listOf("visible")))
        runCurrent()
        val visibleStart = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork

        assertEquals(render("visible"), visibleStart.request)
        actor.send(TimelineMosaicQueueCommand.WorkerFinished(visibleStart.request, visibleStart.token))
        runCurrent()
        assertEquals(render("offscreen"), (actor.effects.receive() as TimelineMosaicQueueEffect.StartWork).request)
        closeActor(actor)
    }

    @Test
    fun invalidatingRunningWorkEmitsCancelAndRemovesQueuedState() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(enqueue(listOf(render("running"), render("queued"))))
        runCurrent()
        val start = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork

        actor.send(TimelineMosaicQueueCommand.InvalidateBucket("running"))
        runCurrent()

        assertEquals(
            TimelineMosaicQueueEffect.CancelWork(render("running"), start.token),
            actor.effects.receive()
        )
        assertEquals(render("queued"), (actor.effects.receive() as TimelineMosaicQueueEffect.StartWork).request)
        closeActor(actor)
    }

    @Test
    fun staleWorkerFinishedDoesNotClearNewerRunningWork() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(enqueue(listOf(render("old"))))
        runCurrent()
        val oldStart = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork
        actor.send(enqueue(listOf(render("new")), visiblePriority = true, visible = setOf("new")))
        runCurrent()
        actor.effects.receive()
        val newStart = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork

        actor.send(TimelineMosaicQueueCommand.WorkerFinished(oldStart.request, oldStart.token))
        runCurrent()

        assertEquals(null, actor.effects.tryReceive().getOrNull())
        actor.send(TimelineMosaicQueueCommand.WorkerFinished(newStart.request, newStart.token))
        runCurrent()
        assertEquals(null, actor.effects.tryReceive().getOrNull())
        closeActor(actor)
    }

    private fun enqueue(
        requests: List<TimelineMosaicQueueRequest>,
        visiblePriority: Boolean = false,
        visible: Set<String> = emptySet()
    ): TimelineMosaicQueueCommand.Enqueue =
        TimelineMosaicQueueCommand.Enqueue(
            requests = requests,
            visiblePriority = visiblePriority,
            visibleOrTargetBuckets = visible
        )

    private fun render(timeBucket: String): TimelineMosaicQueueRequest =
        TimelineMosaicQueueRequest(timeBucket, TimelineMosaicWorkSource.RenderDemand)

    private fun sync(timeBucket: String): TimelineMosaicQueueRequest =
        TimelineMosaicQueueRequest(timeBucket, TimelineMosaicWorkSource.SyncPrecompute)

    private fun TestScope.closeActor(actor: TimelineMosaicQueueActor) {
        actor.close()
        runCurrent()
    }
}
