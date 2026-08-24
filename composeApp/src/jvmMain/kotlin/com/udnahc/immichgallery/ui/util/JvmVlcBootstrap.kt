package com.udnahc.immichgallery.ui.util

import java.io.File
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.strategy.LinuxNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.NativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.OsxNativeDiscoveryStrategy
import uk.co.caprica.vlcj.factory.discovery.strategy.WindowsNativeDiscoveryStrategy

internal enum class JvmVlcAvailability {
    Bundled,
    Installed,
    Unavailable;

    val isAvailable: Boolean
        get() = this != Unavailable
}

internal interface JvmVlcDiscovery {
    fun discoverBundled(
        nativeDirectory: File,
        operatingSystem: JvmDesktopOperatingSystem,
    ): Boolean

    fun discoverInstalled(): Boolean
}

internal class DefaultJvmVlcDiscovery : JvmVlcDiscovery {
    override fun discoverBundled(
        nativeDirectory: File,
        operatingSystem: JvmDesktopOperatingSystem,
    ): Boolean = safelyDiscover {
        val delegate = operatingSystem.discoveryStrategy()
        val strategy = ExactBundledDirectoryDiscoveryStrategy(nativeDirectory, delegate)

        // NativeDiscovery deliberately preserves an inherited VLC_PLUGIN_PATH.
        // Set the validated bundled path first so an unrelated installation can
        // never supply plugins for the packaged LibVLC pair.
        if (!strategy.onSetPluginPath(nativeDirectory.absolutePath)) return@safelyDiscover false
        NativeDiscovery(strategy).discover()
    }

    override fun discoverInstalled(): Boolean = safelyDiscover {
        NativeDiscovery().discover()
    }

    private inline fun safelyDiscover(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
}

internal class ExactBundledDirectoryDiscoveryStrategy(
    nativeDirectory: File,
    private val delegate: NativeDiscoveryStrategy,
) : NativeDiscoveryStrategy {
    private val exactDirectory = nativeDirectory.absoluteFile.normalize()

    override fun supported(): Boolean = delegate.supported()

    override fun discover(): String? =
        exactDirectory.takeIf(File::isDirectory)?.absolutePath

    override fun onFound(path: String): Boolean =
        if (isExactPath(path)) delegate.onFound(path) else false

    override fun onSetPluginPath(path: String): Boolean =
        if (isExactPath(path)) delegate.onSetPluginPath(path) else false

    private fun isExactPath(path: String): Boolean =
        File(path).absoluteFile.normalize() == exactDirectory
}

internal class JvmVlcBootstrapEngine(
    private val resourceDirectory: () -> File?,
    private val bundleRequired: () -> Boolean,
    private val target: () -> JvmDesktopTarget?,
    private val setSystemProperty: (String, String) -> Unit,
    private val discovery: JvmVlcDiscovery,
) {
    fun initialize(): JvmVlcAvailability {
        // Compose's development run also supplies an application-resources
        // directory. Native package launchers set a separate required marker,
        // so an empty development resources directory can use installed VLC
        // without making a packaged application depend on the host machine.
        val requiresBundle = bundleRequired()
        val applicationResources = resourceDirectory()
        if (applicationResources == null) {
            return if (requiresBundle) JvmVlcAvailability.Unavailable else discoverInstalled()
        }
        val stagedRoot = applicationResources.resolve(BUNDLED_VLC_RESOURCE_DIRECTORY)
        if (!stagedRoot.exists()) {
            return if (requiresBundle) JvmVlcAvailability.Unavailable else discoverInstalled()
        }
        val currentTarget = target() ?: return JvmVlcAvailability.Unavailable

        val resolution = JvmVlcBundleLayout.resolveStagedRoot(
            stagedRoot = stagedRoot,
            operatingSystem = currentTarget.operatingSystem,
        )
        val found = resolution as? JvmVlcBundleResolution.Found
            ?: return JvmVlcAvailability.Unavailable
        return try {
            setSystemProperty(JNA_LIBRARY_PATH_PROPERTY, found.nativeDirectory.absolutePath)
            if (discovery.discoverBundled(found.nativeDirectory, currentTarget.operatingSystem)) {
                JvmVlcAvailability.Bundled
            } else {
                JvmVlcAvailability.Unavailable
            }
        } catch (_: SecurityException) {
            JvmVlcAvailability.Unavailable
        }
    }

    private fun discoverInstalled(): JvmVlcAvailability =
        if (discovery.discoverInstalled()) {
            JvmVlcAvailability.Installed
        } else {
            JvmVlcAvailability.Unavailable
        }
}

internal object JvmVlcBootstrap {
    @Volatile
    private var initializedAvailability: JvmVlcAvailability? = null

    fun initialize(): JvmVlcAvailability {
        initializedAvailability?.let { return it }
        return synchronized(this) {
            initializedAvailability ?: createDefaultEngine().initialize().also {
                initializedAvailability = it
            }
        }
    }

    fun isAvailable(): Boolean = initialize().isAvailable

    private fun createDefaultEngine(): JvmVlcBootstrapEngine =
        JvmVlcBootstrapEngine(
            resourceDirectory = {
                System.getProperty(COMPOSE_APPLICATION_RESOURCES_PROPERTY)
                    ?.takeIf(String::isNotBlank)
                    ?.let(::File)
            },
            bundleRequired = {
                System.getProperty(BUNDLED_VLC_REQUIRED_PROPERTY).toBoolean()
            },
            target = { JvmDesktopTarget.detect() },
            setSystemProperty = { key, value -> System.setProperty(key, value) },
            discovery = DefaultJvmVlcDiscovery(),
        )
}

private fun JvmDesktopOperatingSystem.discoveryStrategy(): NativeDiscoveryStrategy =
    when (this) {
        JvmDesktopOperatingSystem.Linux -> LinuxNativeDiscoveryStrategy()
        JvmDesktopOperatingSystem.MacOs -> OsxNativeDiscoveryStrategy()
        JvmDesktopOperatingSystem.Windows -> WindowsNativeDiscoveryStrategy()
    }

private const val BUNDLED_VLC_RESOURCE_DIRECTORY = "vlc"
private const val BUNDLED_VLC_REQUIRED_PROPERTY = "immichGallery.vlcBundleRequired"
private const val COMPOSE_APPLICATION_RESOURCES_PROPERTY = "compose.application.resources.dir"
private const val JNA_LIBRARY_PATH_PROPERTY = "jna.library.path"
