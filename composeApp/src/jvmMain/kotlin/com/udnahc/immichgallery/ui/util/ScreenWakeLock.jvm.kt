package com.udnahc.immichgallery.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Composable
actual fun rememberScreenWakeLock(): ScreenWakeLock =
    remember { JvmScreenWakeLock(JvmWakeInhibition.manager) }

internal fun shutdownJvmWakeInhibition() {
    JvmWakeInhibition.manager.shutdown()
}

internal fun installJvmWakeInhibitionShutdownHook() {
    JvmWakeInhibitionShutdownHook.install()
}

internal fun interface JvmShutdownHookRegistrar {
    fun register(action: () -> Unit)
}

internal class JvmWakeInhibitionShutdownHookInstaller(
    private val registrar: JvmShutdownHookRegistrar,
    private val shutdown: () -> Unit,
) {
    private var isInstalled = false

    @Synchronized
    fun install(): Boolean {
        if (isInstalled) return false
        return try {
            registrar.register(shutdown)
            isInstalled = true
            true
        } catch (_: RuntimeException) {
            false
        }
    }
}

internal interface DesktopWakeInhibitor {
    fun start(): Boolean
    fun stop()
    fun close()
}

internal class DesktopWakeInhibitionManager(
    private val backendFactory: () -> DesktopWakeInhibitor,
) {
    private var backend: DesktopWakeInhibitor? = null
    private var activeOwners = 0
    private var isShutdown = false

    @Synchronized
    fun acquire() {
        if (isShutdown) return
        activeOwners += 1
        if (activeOwners == 1) {
            val currentBackend = backend ?: backendFactory().also { backend = it }
            currentBackend.start()
        }
    }

    @Synchronized
    fun release() {
        if (activeOwners == 0) return
        activeOwners -= 1
        if (activeOwners == 0) backend?.stop()
    }

    @Synchronized
    fun shutdown() {
        if (isShutdown) return
        isShutdown = true
        activeOwners = 0
        backend?.close()
        backend = null
    }
}

internal class JvmScreenWakeLock(
    private val manager: DesktopWakeInhibitionManager,
) : ScreenWakeLock {
    private var isHeld = false

    @Synchronized
    override fun acquire() {
        if (isHeld) return
        isHeld = true
        manager.acquire()
    }

    @Synchronized
    override fun release() {
        if (!isHeld) return
        isHeld = false
        manager.release()
    }
}

internal fun interface OwnedProcessStarter {
    fun start(command: List<String>): OwnedProcess?
}

internal interface OwnedProcess {
    val isAlive: Boolean
    fun stopOwnedTree()
}

internal class CommandWakeInhibitor(
    private val command: List<String>,
    private val processStarter: OwnedProcessStarter,
) : DesktopWakeInhibitor {
    private var ownedProcess: OwnedProcess? = null

    @Synchronized
    override fun start(): Boolean {
        val existingProcess = ownedProcess
        if (existingProcess?.isAlive == true) return true
        existingProcess?.stopOwnedTree()

        val startedProcess = processStarter.start(command)
        if (startedProcess?.isAlive != true) {
            startedProcess?.stopOwnedTree()
            ownedProcess = null
            return false
        }
        ownedProcess = startedProcess
        return true
    }

    @Synchronized
    override fun stop() {
        ownedProcess?.stopOwnedTree()
        ownedProcess = null
    }

    override fun close() = stop()
}

internal fun interface WindowsExecutionStateApi {
    fun setExecutionState(flags: Int): Boolean
}

internal interface WakeOwnerThread {
    fun execute(block: () -> Boolean): Boolean
    fun close()
}

internal class DedicatedWakeOwnerThread(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ImmichGallery-WakeInhibition").apply { isDaemon = true }
    },
) : WakeOwnerThread {
    private var isClosed = false

    @Synchronized
    override fun execute(block: () -> Boolean): Boolean {
        if (isClosed) return false
        return try {
            executor.submit<Boolean> { block() }.get()
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    override fun close() {
        if (isClosed) return
        isClosed = true
        executor.shutdown()
        try {
            if (!executor.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            executor.shutdownNow()
        }
    }
}

internal class WindowsWakeInhibitor(
    private val executionStateApi: WindowsExecutionStateApi,
    private val ownerThread: WakeOwnerThread,
) : DesktopWakeInhibitor {
    private var isActive = false

    @Synchronized
    override fun start(): Boolean {
        if (isActive) return true
        val acquired = ownerThread.execute {
            executionStateApi.setExecutionState(WINDOWS_ACTIVE_EXECUTION_STATE)
        }
        isActive = acquired
        return acquired
    }

    @Synchronized
    override fun stop() {
        if (!isActive) return
        ownerThread.execute {
            executionStateApi.setExecutionState(WINDOWS_RESET_EXECUTION_STATE)
        }
        isActive = false
    }

    @Synchronized
    override fun close() {
        stop()
        ownerThread.close()
    }
}

private const val WINDOWS_ES_CONTINUOUS: Int = Int.MIN_VALUE
private const val WINDOWS_ES_SYSTEM_REQUIRED: Int = 0x00000001
private const val WINDOWS_ES_DISPLAY_REQUIRED: Int = 0x00000002

internal const val WINDOWS_ACTIVE_EXECUTION_STATE: Int =
    WINDOWS_ES_CONTINUOUS or WINDOWS_ES_SYSTEM_REQUIRED or WINDOWS_ES_DISPLAY_REQUIRED
internal const val WINDOWS_RESET_EXECUTION_STATE: Int = WINDOWS_ES_CONTINUOUS

private object JvmWakeInhibition {
    val manager = DesktopWakeInhibitionManager(::createDesktopWakeInhibitor)
}

private object JvmWakeInhibitionShutdownHook {
    private val installer = JvmWakeInhibitionShutdownHookInstaller(
        registrar = JvmShutdownHookRegistrar { action ->
            Runtime.getRuntime().addShutdownHook(
                Thread({ action() }, "ImmichGallery-WakeInhibition-Shutdown")
            )
        },
        shutdown = JvmWakeInhibition.manager::shutdown,
    )

    fun install() {
        installer.install()
    }
}

private fun createDesktopWakeInhibitor(): DesktopWakeInhibitor {
    val target = JvmDesktopTarget.detect() ?: return UnsupportedWakeInhibitor
    return when (target.operatingSystem) {
        JvmDesktopOperatingSystem.MacOs -> CommandWakeInhibitor(
            command = listOf("/usr/bin/caffeinate", "-d", "-i"),
            processStarter = JavaOwnedProcessStarter,
        )
        JvmDesktopOperatingSystem.Linux -> CommandWakeInhibitor(
            command = listOf(
                "systemd-inhibit",
                "--what=idle:sleep",
                "--who=ImmichGallery",
                "--why=Slideshow is active",
                "--mode=block",
                "sleep",
                "infinity",
            ),
            processStarter = JavaOwnedProcessStarter,
        )
        JvmDesktopOperatingSystem.Windows -> createWindowsWakeInhibitor()
    }
}

private fun createWindowsWakeInhibitor(): DesktopWakeInhibitor =
    try {
        val library = Native.load(
            "kernel32",
            Kernel32PowerLibrary::class.java,
            W32APIOptions.DEFAULT_OPTIONS,
        )
        WindowsWakeInhibitor(
            executionStateApi = WindowsExecutionStateApi { flags ->
                library.SetThreadExecutionState(flags) != 0
            },
            ownerThread = DedicatedWakeOwnerThread(),
        )
    } catch (_: Exception) {
        UnsupportedWakeInhibitor
    } catch (_: LinkageError) {
        UnsupportedWakeInhibitor
    }

private interface Kernel32PowerLibrary : StdCallLibrary {
    fun SetThreadExecutionState(executionState: Int): Int
}

private object UnsupportedWakeInhibitor : DesktopWakeInhibitor {
    override fun start(): Boolean = false
    override fun stop() = Unit
    override fun close() = Unit
}

private object JavaOwnedProcessStarter : OwnedProcessStarter {
    override fun start(command: List<String>): OwnedProcess? =
        try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            JavaOwnedProcess(process)
        } catch (_: Exception) {
            null
        }
}

private class JavaOwnedProcess(
    private val process: Process,
) : OwnedProcess {
    override val isAlive: Boolean
        get() = process.isAlive

    override fun stopOwnedTree() {
        val descendants = try {
            process.toHandle().descendants().iterator().asSequence().toList()
        } catch (_: Exception) {
            emptyList()
        }
        descendants.asReversed().forEach { child ->
            try {
                if (child.isAlive) child.destroy()
            } catch (_: Exception) {
                // Continue to the root process; every handle here belongs to it.
            }
        }
        try {
            process.destroy()
        } catch (_: Exception) {
            try {
                process.destroyForcibly()
            } catch (_: Exception) {
                // The process may already have exited.
            }
            return
        }
        try {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
        }
        descendants.asReversed().forEach { child ->
            try {
                if (child.isAlive) child.destroyForcibly()
            } catch (_: Exception) {
                // The child may already have exited between checks.
            }
        }
    }
}
