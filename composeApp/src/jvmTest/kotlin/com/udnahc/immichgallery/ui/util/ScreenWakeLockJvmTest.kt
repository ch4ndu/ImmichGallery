package com.udnahc.immichgallery.ui.util

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenWakeLockJvmTest {
    @Test
    fun handlesAreIdempotentAndShareOneBalancedBackend() {
        val backend = RecordingWakeInhibitor()
        val manager = DesktopWakeInhibitionManager { backend }
        val first = JvmScreenWakeLock(manager)
        val second = JvmScreenWakeLock(manager)

        first.acquire()
        first.acquire()
        second.acquire()
        assertEquals(1, backend.starts)

        first.release()
        first.release()
        assertEquals(0, backend.stops)
        second.release()
        assertEquals(1, backend.stops)

        first.acquire()
        assertEquals(2, backend.starts)
        manager.shutdown()
        assertEquals(1, backend.closes)

        first.release()
        first.acquire()
        assertEquals(2, backend.starts)
    }

    @Test
    fun commandInhibitorStopsOnlyTheProcessItStarted() {
        val owned = RecordingOwnedProcess(isAlive = true)
        val unrelated = RecordingOwnedProcess(isAlive = true)
        val commands = mutableListOf<List<String>>()
        val inhibitor = CommandWakeInhibitor(
            command = listOf("inhibitor", "--reason", "slideshow"),
            processStarter = OwnedProcessStarter { command ->
                commands += command
                owned
            },
        )

        assertTrue(inhibitor.start())
        assertTrue(inhibitor.start())
        assertEquals(1, commands.size)
        inhibitor.stop()
        inhibitor.stop()

        assertEquals(1, owned.stopCalls)
        assertEquals(0, unrelated.stopCalls)
    }

    @Test
    fun missingCommandIsASafeUnsupportedFallback() {
        val inhibitor = CommandWakeInhibitor(
            command = listOf("missing-inhibitor"),
            processStarter = OwnedProcessStarter { null },
        )

        assertFalse(inhibitor.start())
        inhibitor.stop()
        inhibitor.close()
    }

    @Test
    fun windowsAcquireResetAndShutdownUseOneDedicatedOwnerThread() {
        val flags = Collections.synchronizedList(mutableListOf<Int>())
        val threadNames = Collections.synchronizedList(mutableListOf<String>())
        val inhibitor = WindowsWakeInhibitor(
            executionStateApi = WindowsExecutionStateApi { state ->
                flags += state
                threadNames += Thread.currentThread().name
                true
            },
            ownerThread = DedicatedWakeOwnerThread(),
        )

        assertTrue(inhibitor.start())
        assertTrue(inhibitor.start())
        inhibitor.stop()
        inhibitor.stop()
        assertTrue(inhibitor.start())
        inhibitor.close()

        assertEquals(
            listOf(
                WINDOWS_ACTIVE_EXECUTION_STATE,
                WINDOWS_RESET_EXECUTION_STATE,
                WINDOWS_ACTIVE_EXECUTION_STATE,
                WINDOWS_RESET_EXECUTION_STATE,
            ),
            flags,
        )
        assertEquals(1, threadNames.distinct().size)
        assertEquals("ImmichGallery-WakeInhibition", threadNames.distinct().single())
    }

    @Test
    fun shutdownHookRegistersOnceAndReleasesTheOwnedBackendOnce() {
        val hooks = mutableListOf<() -> Unit>()
        val backend = RecordingWakeInhibitor()
        val manager = DesktopWakeInhibitionManager { backend }
        val installer = JvmWakeInhibitionShutdownHookInstaller(
            registrar = JvmShutdownHookRegistrar { action -> hooks += action },
            shutdown = manager::shutdown,
        )

        assertTrue(installer.install())
        assertFalse(installer.install())
        assertEquals(1, hooks.size)

        manager.acquire()
        hooks.single().invoke()
        hooks.single().invoke()
        manager.acquire()

        assertEquals(1, backend.starts)
        assertEquals(1, backend.closes)
    }
}

private class RecordingWakeInhibitor : DesktopWakeInhibitor {
    var starts = 0
    var stops = 0
    var closes = 0

    override fun start(): Boolean {
        starts += 1
        return true
    }

    override fun stop() {
        stops += 1
    }

    override fun close() {
        closes += 1
    }
}

private class RecordingOwnedProcess(
    override var isAlive: Boolean,
) : OwnedProcess {
    var stopCalls = 0

    override fun stopOwnedTree() {
        stopCalls += 1
        isAlive = false
    }
}
