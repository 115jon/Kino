package com.nuvio.app

import org.jetbrains.skiko.GraphicsApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsVrrCompatibilityTest {
    @Test
    fun hardwareRenderersUseVrrCompatibility() {
        assertTrue(shouldInstallWindowsVrrCompatibility(GraphicsApi.DIRECT3D))
        assertTrue(shouldInstallWindowsVrrCompatibility(GraphicsApi.OPENGL))
    }

    @Test
    fun nonHardwareRenderersDoNotUseVrrCompatibility() {
        assertFalse(shouldInstallWindowsVrrCompatibility(GraphicsApi.ANGLE))
        assertFalse(shouldInstallWindowsVrrCompatibility(GraphicsApi.SOFTWARE_FAST))
        assertFalse(shouldInstallWindowsVrrCompatibility(GraphicsApi.SOFTWARE_COMPAT))
    }
}
