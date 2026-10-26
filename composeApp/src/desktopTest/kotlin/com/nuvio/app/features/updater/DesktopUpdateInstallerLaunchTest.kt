package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdateInstallerLaunchTest {
    @Test
    fun `windows installer is created by wmi so it outlives the app job`() {
        val command = windowsDetachedInstallerLaunchCommand()

        assertEquals("powershell.exe", command.first())
        assertTrue("-NonInteractive" in command)
        assertTrue(command.last().contains("Invoke-CimMethod -ClassName Win32_Process -MethodName Create"))
        assertTrue(command.last().contains("\$env:KINO_UPDATE_INSTALLER"))
        assertFalse(command.any { it.contains("Kino-Desktop") })
    }

    @Test
    fun `windows uses a detached installer launch`() {
        assertTrue(shouldLaunchWindowsInstallerDetached("Windows 11"))
        assertTrue(shouldLaunchWindowsInstallerDetached("Windows 10"))
        assertFalse(shouldLaunchWindowsInstallerDetached("Mac OS X"))
        assertFalse(shouldLaunchWindowsInstallerDetached("Linux"))
    }
}
