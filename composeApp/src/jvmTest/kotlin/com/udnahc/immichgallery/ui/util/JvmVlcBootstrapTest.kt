package com.udnahc.immichgallery.ui.util

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy

class JvmVlcBootstrapTest {
    @Test
    fun detectsSupportedDesktopTargetAliases() {
        assertEquals(
            JvmDesktopTarget(JvmDesktopOperatingSystem.MacOs, JvmDesktopArchitecture.Arm64),
            JvmDesktopTarget.detect("Mac OS X", "aarch64"),
        )
        assertEquals(
            JvmDesktopTarget(JvmDesktopOperatingSystem.Windows, JvmDesktopArchitecture.X64),
            JvmDesktopTarget.detect("Windows 11", "amd64"),
        )
        assertEquals(
            JvmDesktopTarget(JvmDesktopOperatingSystem.Linux, JvmDesktopArchitecture.X64),
            JvmDesktopTarget.detect("Linux", "x86_64"),
        )
        assertEquals(null, JvmDesktopTarget.detect("Plan 9", "x86_64"))
        assertEquals(null, JvmDesktopTarget.detect("Linux", "riscv64"))
    }

    @Test
    fun validatesWindowsBundleLayout() = withTempDirectory { stagedRoot ->
        createWindowsBundle(stagedRoot)

        val found = assertIs<JvmVlcBundleResolution.Found>(
            JvmVlcBundleLayout.resolveStagedRoot(stagedRoot, JvmDesktopOperatingSystem.Windows)
        )

        assertEquals(stagedRoot.absoluteFile.normalize(), found.nativeDirectory)
        assertEquals(stagedRoot.resolve("plugins").absoluteFile.normalize(), found.pluginDirectory)
    }

    @Test
    fun validatesLinuxDirectAndNestedPluginLayouts() = withTempDirectory { root ->
        val direct = root.resolve("direct").apply { mkdirs() }
        createLinuxBundle(direct, pluginPath = "plugins")
        assertIs<JvmVlcBundleResolution.Found>(
            JvmVlcBundleLayout.resolveStagedRoot(direct, JvmDesktopOperatingSystem.Linux)
        )

        val nested = root.resolve("nested").apply { mkdirs() }
        createLinuxBundle(nested, pluginPath = "vlc/plugins")
        val found = assertIs<JvmVlcBundleResolution.Found>(
            JvmVlcBundleLayout.resolveStagedRoot(nested, JvmDesktopOperatingSystem.Linux)
        )
        assertEquals(nested.resolve("vlc/plugins").absoluteFile.normalize(), found.pluginDirectory)
    }

    @Test
    fun validatesMacBundleLayoutWithSiblingPlugins() = withTempDirectory { stagedRoot ->
        createMacBundle(stagedRoot)

        val found = assertIs<JvmVlcBundleResolution.Found>(
            JvmVlcBundleLayout.resolveStagedRoot(stagedRoot, JvmDesktopOperatingSystem.MacOs)
        )

        assertEquals(stagedRoot.resolve("lib").absoluteFile.normalize(), found.nativeDirectory)
        assertEquals(stagedRoot.resolve("plugins").absoluteFile.normalize(), found.pluginDirectory)
    }

    @Test
    fun rejectsMissingLibrariesAndPlugins() = withTempDirectory { root ->
        val missingLibraries = assertIs<JvmVlcBundleResolution.Invalid>(
            JvmVlcBundleLayout.resolveStagedRoot(root, JvmDesktopOperatingSystem.Windows)
        )
        assertEquals(JvmVlcBundleIssue.MissingLibVlc, missingLibraries.issue)

        root.resolve("libvlc.dll").writeText("libvlc")
        val missingCore = assertIs<JvmVlcBundleResolution.Invalid>(
            JvmVlcBundleLayout.resolveStagedRoot(root, JvmDesktopOperatingSystem.Windows)
        )
        assertEquals(JvmVlcBundleIssue.MissingLibVlcCore, missingCore.issue)

        root.resolve("libvlccore.dll").writeText("libvlccore")
        val missingPlugins = assertIs<JvmVlcBundleResolution.Invalid>(
            JvmVlcBundleLayout.resolveStagedRoot(root, JvmDesktopOperatingSystem.Windows)
        )
        assertEquals(JvmVlcBundleIssue.MissingPlugins, missingPlugins.issue)
    }

    @Test
    fun rejectsPluginDirectoriesWithoutAnOperatingSystemBinary() = withTempDirectory { root ->
        root.resolve("libvlc.dll").writeText("libvlc")
        root.resolve("libvlccore.dll").writeText("libvlccore")
        root.resolve("plugins/README.txt").apply {
            parentFile.mkdirs()
            writeText("not a plugin")
        }
        root.resolve("plugins/video/plugin.so").apply {
            parentFile.mkdirs()
            writeText("wrong operating system")
        }

        val result = assertIs<JvmVlcBundleResolution.Invalid>(
            JvmVlcBundleLayout.resolveStagedRoot(root, JvmDesktopOperatingSystem.Windows)
        )

        assertEquals(JvmVlcBundleIssue.MissingPlugins, result.issue)
    }

    @Test
    fun rejectsEmptyFirstPluginDirectoryThatWouldShadowValidNestedPlugins() = withTempDirectory { root ->
        root.resolve("libvlc.so").writeText("libvlc")
        root.resolve("libvlccore.so").writeText("libvlccore")
        root.resolve("plugins").mkdirs()
        root.resolve("vlc/plugins/video/plugin.so").apply {
            parentFile.mkdirs()
            writeText("plugin")
        }

        val result = assertIs<JvmVlcBundleResolution.Invalid>(
            JvmVlcBundleLayout.resolveStagedRoot(root, JvmDesktopOperatingSystem.Linux)
        )

        assertEquals(JvmVlcBundleIssue.MissingPlugins, result.issue)
    }

    @Test
    fun sourceRootRequiresExactOperatingSystemAndArchitectureDirectory() = withTempDirectory { root ->
        val x64Target = JvmDesktopTarget(
            JvmDesktopOperatingSystem.Windows,
            JvmDesktopArchitecture.X64,
        )
        createWindowsBundle(root.resolve(x64Target.id).apply { mkdirs() })

        assertIs<JvmVlcBundleResolution.Found>(
            JvmVlcBundleLayout.resolveSourceRoot(root, x64Target)
        )
        val mismatch = assertIs<JvmVlcBundleResolution.Invalid>(
            JvmVlcBundleLayout.resolveSourceRoot(
                root,
                x64Target.copy(architecture = JvmDesktopArchitecture.Arm64),
            )
        )
        assertEquals(JvmVlcBundleIssue.MissingTarget, mismatch.issue)
    }

    @Test
    fun bundledBootstrapUsesExactDirectoryEvenWhenPathContainsSpaces() = withTempDirectory("vlc bundle resources ") { resources ->
        val stagedRoot = resources.resolve("vlc").apply { mkdirs() }
        createMacBundle(stagedRoot)
        val discovery = RecordingVlcDiscovery(bundledResult = true, installedResult = true)
        val systemProperties = mutableMapOf<String, String>()

        val availability = JvmVlcBootstrapEngine(
            resourceDirectory = { resources },
            bundleRequired = { true },
            target = {
                JvmDesktopTarget(JvmDesktopOperatingSystem.MacOs, JvmDesktopArchitecture.Arm64)
            },
            setSystemProperty = systemProperties::set,
            discovery = discovery,
        ).initialize()

        assertEquals(JvmVlcAvailability.Bundled, availability)
        assertEquals(1, discovery.bundledCalls)
        assertEquals(0, discovery.installedCalls)
        assertEquals(stagedRoot.resolve("lib").absolutePath, systemProperties["jna.library.path"])
        assertEquals(stagedRoot.resolve("lib").absoluteFile.normalize(), discovery.bundledDirectory)
    }

    @Test
    fun packagedResourcesWithoutVlcFailClosedWithoutInstalledFallback() = withTempDirectory { resources ->
        val discovery = RecordingVlcDiscovery(bundledResult = true, installedResult = true)

        val availability = JvmVlcBootstrapEngine(
            resourceDirectory = { resources },
            bundleRequired = { true },
            target = {
                JvmDesktopTarget(JvmDesktopOperatingSystem.Linux, JvmDesktopArchitecture.X64)
            },
            setSystemProperty = { _, _ -> },
            discovery = discovery,
        ).initialize()

        assertEquals(JvmVlcAvailability.Unavailable, availability)
        assertEquals(0, discovery.bundledCalls)
        assertEquals(0, discovery.installedCalls)
    }

    @Test
    fun developmentResourcesWithoutVlcUseInstalledFallback() = withTempDirectory { resources ->
        val discovery = RecordingVlcDiscovery(bundledResult = false, installedResult = true)

        val availability = JvmVlcBootstrapEngine(
            resourceDirectory = { resources },
            bundleRequired = { false },
            target = { null },
            setSystemProperty = { _, _ -> },
            discovery = discovery,
        ).initialize()

        assertEquals(JvmVlcAvailability.Installed, availability)
        assertEquals(0, discovery.bundledCalls)
        assertEquals(1, discovery.installedCalls)
    }

    @Test
    fun invalidPackagedBundleDoesNotFallBackToInstalledVlc() = withTempDirectory { resources ->
        resources.resolve("vlc").mkdirs()
        val discovery = RecordingVlcDiscovery(bundledResult = true, installedResult = true)

        val availability = JvmVlcBootstrapEngine(
            resourceDirectory = { resources },
            bundleRequired = { true },
            target = {
                JvmDesktopTarget(JvmDesktopOperatingSystem.Windows, JvmDesktopArchitecture.X64)
            },
            setSystemProperty = { _, _ -> },
            discovery = discovery,
        ).initialize()

        assertEquals(JvmVlcAvailability.Unavailable, availability)
        assertEquals(0, discovery.bundledCalls)
        assertEquals(0, discovery.installedCalls)
    }

    @Test
    fun installedDiscoveryIsOnlyUsedWithoutComposeApplicationResources() {
        val discovery = RecordingVlcDiscovery(bundledResult = false, installedResult = true)

        val availability = JvmVlcBootstrapEngine(
            resourceDirectory = { null },
            bundleRequired = { false },
            target = { null },
            setSystemProperty = { _, _ -> },
            discovery = discovery,
        ).initialize()

        assertEquals(JvmVlcAvailability.Installed, availability)
        assertEquals(0, discovery.bundledCalls)
        assertEquals(1, discovery.installedCalls)
    }

    @Test
    fun packagedModeWithoutApplicationResourcesFailsClosed() {
        val discovery = RecordingVlcDiscovery(bundledResult = false, installedResult = true)

        val availability = JvmVlcBootstrapEngine(
            resourceDirectory = { null },
            bundleRequired = { true },
            target = { null },
            setSystemProperty = { _, _ -> },
            discovery = discovery,
        ).initialize()

        assertEquals(JvmVlcAvailability.Unavailable, availability)
        assertEquals(0, discovery.bundledCalls)
        assertEquals(0, discovery.installedCalls)
    }

    @Test
    fun failedBundledDiscoveryDoesNotFallBackToInstalledVlc() = withTempDirectory { resources ->
        createLinuxBundle(resources.resolve("vlc").apply { mkdirs() }, pluginPath = "plugins")
        val discovery = RecordingVlcDiscovery(bundledResult = false, installedResult = true)

        val availability = JvmVlcBootstrapEngine(
            resourceDirectory = { resources },
            bundleRequired = { true },
            target = {
                JvmDesktopTarget(JvmDesktopOperatingSystem.Linux, JvmDesktopArchitecture.X64)
            },
            setSystemProperty = { _, _ -> },
            discovery = discovery,
        ).initialize()

        assertEquals(JvmVlcAvailability.Unavailable, availability)
        assertEquals(1, discovery.bundledCalls)
        assertEquals(0, discovery.installedCalls)
    }

    @Test
    fun exactStrategyNeverSearchesDelegateProvidersOrAnotherDirectory() = withTempDirectory { nativeDirectory ->
        val delegate = RecordingNativeDiscoveryStrategy()
        val strategy = ExactBundledDirectoryDiscoveryStrategy(nativeDirectory, delegate)
        val differentDirectory = nativeDirectory.resolve("other").apply { mkdirs() }

        assertTrue(strategy.supported())
        assertEquals(nativeDirectory.absolutePath, strategy.discover())
        assertTrue(strategy.onFound(nativeDirectory.absolutePath))
        assertTrue(strategy.onSetPluginPath(nativeDirectory.absolutePath))
        assertFalse(strategy.onFound(differentDirectory.absolutePath))
        assertFalse(strategy.onSetPluginPath(differentDirectory.absolutePath))
        assertEquals(1, delegate.foundCalls)
        assertEquals(1, delegate.pluginCalls)
    }

    private fun createWindowsBundle(root: File) {
        root.resolve("libvlc.dll").writeText("libvlc")
        root.resolve("libvlccore.dll").writeText("libvlccore")
        root.resolve("plugins/video/plugin.dll").apply {
            parentFile.mkdirs()
            writeText("plugin")
        }
    }

    private fun createLinuxBundle(root: File, pluginPath: String) {
        root.resolve("libvlc.so.5").writeText("libvlc")
        root.resolve("libvlccore.so.9.0").writeText("libvlccore")
        root.resolve("$pluginPath/video/plugin.so").apply {
            parentFile.mkdirs()
            writeText("plugin")
        }
    }

    private fun createMacBundle(root: File) {
        root.resolve("lib/libvlc.dylib").apply {
            parentFile.mkdirs()
            writeText("libvlc")
        }
        root.resolve("lib/libvlccore.dylib").writeText("libvlccore")
        root.resolve("plugins/video/plugin.dylib").apply {
            parentFile.mkdirs()
            writeText("plugin")
        }
    }

    private inline fun withTempDirectory(
        prefix: String = "immichgallery-vlc-test-",
        block: (File) -> Unit,
    ) {
        val directory = Files.createTempDirectory(prefix).toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}

private class RecordingVlcDiscovery(
    private val bundledResult: Boolean,
    private val installedResult: Boolean,
) : JvmVlcDiscovery {
    var bundledCalls = 0
    var installedCalls = 0
    var bundledDirectory: File? = null

    override fun discoverBundled(
        nativeDirectory: File,
        operatingSystem: JvmDesktopOperatingSystem,
    ): Boolean {
        bundledCalls += 1
        bundledDirectory = nativeDirectory
        return bundledResult
    }

    override fun discoverInstalled(): Boolean {
        installedCalls += 1
        return installedResult
    }
}

private class RecordingNativeDiscoveryStrategy : NativeDiscoveryStrategy {
    var foundCalls = 0
    var pluginCalls = 0

    override fun supported(): Boolean = true
    override fun discover(): String = error("The exact strategy must not ask the delegate to discover")

    override fun onFound(path: String): Boolean {
        foundCalls += 1
        return true
    }

    override fun onSetPluginPath(path: String): Boolean {
        pluginCalls += 1
        return true
    }
}
