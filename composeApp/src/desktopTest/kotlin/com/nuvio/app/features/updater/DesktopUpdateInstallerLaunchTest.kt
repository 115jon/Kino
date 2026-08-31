package com.nuvio.app.features.updater

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopUpdateInstallerLaunchTest {
    @Test
    fun `windows silent installer is launched detached from the current process`() {
        val installer = File("C:\\Users\\kino\\AppData\\Local\\Temp\\kino-update\\Kino-Desktop-0.4.0.exe")

        val command = windowsSilentInstallerLaunchCommand(installer.absolutePath)

        assertEquals("cmd.exe", command[0])
        assertEquals("/c", command[1])
        assertEquals("start", command[2])
        assertEquals("", command[3])
        assertEquals(installer.absolutePath, command[4])
        assertEquals("/S", command[5])
        assertFalse(command[0].equals(installer.absolutePath, ignoreCase = true))
    }

    @Test
    fun `windows uses a detached installer launch`() {
        assertTrue(shouldLaunchWindowsInstallerDetached("Windows 11"))
        assertTrue(shouldLaunchWindowsInstallerDetached("Windows 10"))
        assertFalse(shouldLaunchWindowsInstallerDetached("Mac OS X"))
        assertFalse(shouldLaunchWindowsInstallerDetached("Linux"))
    }
}
