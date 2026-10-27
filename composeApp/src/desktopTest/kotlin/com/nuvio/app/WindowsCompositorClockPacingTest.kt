package com.nuvio.app

import org.jetbrains.skiko.GraphicsApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsCompositorClockPacingTest {
    @Test
    fun openGlIsPacedByTheCompositorClock() {
        assertTrue(shouldPaceWithCompositorClock(GraphicsApi.OPENGL))
    }

    @Test
    fun renderersWithTheirOwnPresentPacingAreLeftAlone() {
        assertFalse(shouldPaceWithCompositorClock(GraphicsApi.DIRECT3D))
        assertFalse(shouldPaceWithCompositorClock(GraphicsApi.ANGLE))
        assertFalse(shouldPaceWithCompositorClock(GraphicsApi.SOFTWARE_FAST))
    }
}
