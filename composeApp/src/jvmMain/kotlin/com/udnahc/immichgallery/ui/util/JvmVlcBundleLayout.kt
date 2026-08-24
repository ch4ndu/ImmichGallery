package com.udnahc.immichgallery.ui.util

import java.io.File

internal sealed interface JvmVlcBundleResolution {
    data class Found(
        val nativeDirectory: File,
        val pluginDirectory: File,
    ) : JvmVlcBundleResolution

    data class Invalid(val issue: JvmVlcBundleIssue) : JvmVlcBundleResolution

    data object Missing : JvmVlcBundleResolution
}

internal enum class JvmVlcBundleIssue {
    MissingLibVlc,
    MissingLibVlcCore,
    MissingPlugins,
    MissingTarget,
}

internal object JvmVlcBundleLayout {
    fun resolveSourceRoot(
        bundleRoot: File,
        target: JvmDesktopTarget,
    ): JvmVlcBundleResolution {
        val targetDirectory = bundleRoot.resolve(target.id)
        if (!targetDirectory.isDirectory) {
            return JvmVlcBundleResolution.Invalid(JvmVlcBundleIssue.MissingTarget)
        }
        return resolveStagedRoot(targetDirectory, target.operatingSystem)
    }

    fun resolveStagedRoot(
        stagedRoot: File,
        operatingSystem: JvmDesktopOperatingSystem,
    ): JvmVlcBundleResolution {
        if (!stagedRoot.isDirectory) return JvmVlcBundleResolution.Missing

        val nativeDirectory = when (operatingSystem) {
            JvmDesktopOperatingSystem.MacOs -> stagedRoot.resolve("lib")
            JvmDesktopOperatingSystem.Linux,
            JvmDesktopOperatingSystem.Windows -> stagedRoot
        }
        if (!nativeDirectory.hasFileMatching(operatingSystem.libVlcPattern)) {
            return JvmVlcBundleResolution.Invalid(JvmVlcBundleIssue.MissingLibVlc)
        }
        if (!nativeDirectory.hasFileMatching(operatingSystem.libVlcCorePattern)) {
            return JvmVlcBundleResolution.Invalid(JvmVlcBundleIssue.MissingLibVlcCore)
        }

        val pluginDirectory = operatingSystem.pluginDirectories(nativeDirectory)
            .firstOrNull(File::exists)
            ?: return JvmVlcBundleResolution.Invalid(JvmVlcBundleIssue.MissingPlugins)
        val hasPluginBinary = pluginDirectory.isDirectory &&
            pluginDirectory.walkTopDown().any { candidate ->
                candidate.isFile && operatingSystem.pluginBinaryPattern.matches(candidate.name)
            }
        if (!hasPluginBinary) {
            return JvmVlcBundleResolution.Invalid(JvmVlcBundleIssue.MissingPlugins)
        }
        return JvmVlcBundleResolution.Found(
            nativeDirectory = nativeDirectory.absoluteFile.normalize(),
            pluginDirectory = pluginDirectory.absoluteFile.normalize(),
        )
    }
}

private val JvmDesktopOperatingSystem.libVlcPattern: Regex
    get() = when (this) {
        JvmDesktopOperatingSystem.Linux -> Regex("libvlc\\.so(?:\\.\\d+)*")
        JvmDesktopOperatingSystem.MacOs -> Regex("libvlc\\.dylib")
        JvmDesktopOperatingSystem.Windows -> Regex("libvlc\\.dll", RegexOption.IGNORE_CASE)
    }

private val JvmDesktopOperatingSystem.libVlcCorePattern: Regex
    get() = when (this) {
        JvmDesktopOperatingSystem.Linux -> Regex("libvlccore\\.so(?:\\.\\d+)*")
        JvmDesktopOperatingSystem.MacOs -> Regex("libvlccore\\.dylib")
        JvmDesktopOperatingSystem.Windows -> Regex("libvlccore\\.dll", RegexOption.IGNORE_CASE)
    }

private val JvmDesktopOperatingSystem.pluginBinaryPattern: Regex
    get() = when (this) {
        JvmDesktopOperatingSystem.Linux -> Regex(".+\\.so(?:\\.\\d+)*")
        JvmDesktopOperatingSystem.MacOs -> Regex(".+\\.dylib")
        JvmDesktopOperatingSystem.Windows -> Regex(".+\\.dll", RegexOption.IGNORE_CASE)
    }

private fun JvmDesktopOperatingSystem.pluginDirectories(nativeDirectory: File): List<File> =
    when (this) {
        JvmDesktopOperatingSystem.Linux,
        JvmDesktopOperatingSystem.Windows -> listOf(
            nativeDirectory.resolve("plugins"),
            nativeDirectory.resolve("vlc/plugins"),
        )
        JvmDesktopOperatingSystem.MacOs -> listOf(nativeDirectory.resolve("../plugins"))
    }

private fun File.hasFileMatching(pattern: Regex): Boolean =
    listFiles()?.any { candidate -> candidate.isFile && pattern.matches(candidate.name) } == true
