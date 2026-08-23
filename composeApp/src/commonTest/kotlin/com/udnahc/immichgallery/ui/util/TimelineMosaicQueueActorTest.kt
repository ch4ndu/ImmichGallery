package com.udnahc.immichgallery.ui.util

import com.udnahc.immichgallery.data.repository.SessionOperationStamp
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
    fun pauseLetsRunningWorkFinishWithoutStartingAnother() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(enqueue(listOf(render("running"), render("queued"))))
        runCurrent()
        val start = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork

        actor.send(TimelineMosaicQueueCommand.PauseForScroll)
        runCurrent()

        assertEquals(null, actor.effects.tryReceive().getOrNull())
        actor.send(TimelineMosaicQueueCommand.WorkerFinished(start.request, start.token))
        runCurrent()
        assertEquals(null, actor.effects.tryReceive().getOrNull())
        actor.send(TimelineMosaicQueueCommand.ResumeAfterScroll(listOf("visible"), stamp()))
        runCurrent()
        assertEquals(render("visible"), (actor.effects.receive() as TimelineMosaicQueueEffect.StartWork).request)
        closeActor(actor)
    }

    @Test
    fun renderDemandReceivedWhilePausedIsSupersededByResumePriority() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(TimelineMosaicQueueCommand.PauseForScroll)
        actor.send(enqueue(listOf(render("obsolete"))))
        actor.send(TimelineMosaicQueueCommand.Defer(listOf(render("also_obsolete"))))
        actor.send(TimelineMosaicQueueCommand.ResumeAfterScroll(listOf("visible"), stamp()))
        runCurrent()

        assertEquals(render("visible"), (actor.effects.receive() as TimelineMosaicQueueEffect.StartWork).request)
        assertEquals(null, actor.effects.tryReceive().getOrNull())
        closeActor(actor)
    }

    @Test
    fun resumeStartsPriorityBucketsWithoutObsoleteRenderWork() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(TimelineMosaicQueueCommand.PauseForScroll)
        actor.send(TimelineMosaicQueueCommand.Defer(listOf(render("offscreen"))))
        actor.send(TimelineMosaicQueueCommand.ResumeAfterScroll(listOf("visible"), stamp()))
        runCurrent()
        val visibleStart = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork

        assertEquals(render("visible"), visibleStart.request)
        actor.send(TimelineMosaicQueueCommand.WorkerFinished(visibleStart.request, visibleStart.token))
        runCurrent()
        assertEquals(null, actor.effects.tryReceive().getOrNull())
        closeActor(actor)
    }

    @Test
    fun resumePriorityRequestKeepsOriginatingSessionGeneration() = runTest {
        val actor = TimelineMosaicQueueActor(this)
        actor.send(TimelineMosaicQueueCommand.PauseForScroll)
        actor.send(
            TimelineMosaicQueueCommand.ResumeAfterScroll(
                listOf("visible"),
                SessionOperationStamp(7L, 3L)
            )
        )
        runCurrent()

        val start = actor.effects.receive() as TimelineMosaicQueueEffect.StartWork

        assertEquals(SessionOperationStamp(7L, 3L), start.request.sessionStamp)
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
        TimelineMosaicQueueRequest(timeBucket, TimelineMosaicWorkSource.RenderDemand, stamp())

    private fun sync(timeBucket: String): TimelineMosaicQueueRequest =
        TimelineMosaicQueueRequest(timeBucket, TimelineMosaicWorkSource.SyncPrecompute, stamp())

    private fun stamp(): SessionOperationStamp = SessionOperationStamp(0L, 0L)

    private fun TestScope.closeActor(actor: TimelineMosaicQueueActor) {
        actor.close()
        runCurrent()
    }
}
