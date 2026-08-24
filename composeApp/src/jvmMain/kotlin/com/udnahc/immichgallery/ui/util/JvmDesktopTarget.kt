package com.udnahc.immichgallery.ui.util

internal enum class JvmDesktopOperatingSystem {
    Linux,
    MacOs,
    Windows,
}

internal enum class JvmDesktopArchitecture(val id: String) {
    Arm64("arm64"),
    X64("x64"),
}

internal data class JvmDesktopTarget(
    val operatingSystem: JvmDesktopOperatingSystem,
    val architecture: JvmDesktopArchitecture,
) {
    val id: String = "${operatingSystem.id}-${architecture.id}"

    companion object {
        fun detect(
            osName: String = System.getProperty("os.name").orEmpty(),
            osArch: String = System.getProperty("os.arch").orEmpty(),
        ): JvmDesktopTarget? {
            val operatingSystem = when {
                osName.equals("Mac OS X", ignoreCase = true) -> JvmDesktopOperatingSystem.MacOs
                osName.startsWith("Win", ignoreCase = true) -> JvmDesktopOperatingSystem.Windows
                osName.startsWith("Linux", ignoreCase = true) -> JvmDesktopOperatingSystem.Linux
                else -> return null
            }
            val architecture = when (osArch.lowercase()) {
                "aarch64", "arm64" -> JvmDesktopArchitecture.Arm64
                "amd64", "x86_64" -> JvmDesktopArchitecture.X64
                else -> return null
            }
            return JvmDesktopTarget(operatingSystem, architecture)
        }
    }
}

private val JvmDesktopOperatingSystem.id: String
    get() = when (this) {
        JvmDesktopOperatingSystem.Linux -> "linux"
        JvmDesktopOperatingSystem.MacOs -> "macos"
        JvmDesktopOperatingSystem.Windows -> "windows"
    }
