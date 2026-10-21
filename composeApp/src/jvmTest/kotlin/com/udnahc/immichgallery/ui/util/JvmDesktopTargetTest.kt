package com.udnahc.immichgallery.ui.util

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmDesktopTargetTest {
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
}
