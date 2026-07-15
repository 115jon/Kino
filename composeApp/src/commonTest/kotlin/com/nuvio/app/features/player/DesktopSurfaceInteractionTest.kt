package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopSurfaceInteractionTest {
    @Test
    fun surfaceExitIsReportedOnlyOnInsideToOutsideTransition() {
        assertEquals(false, shouldNotifyPlayerSurfaceExit(wasInside = false, isInside = false))
        assertEquals(false, shouldNotifyPlayerSurfaceExit(wasInside = false, isInside = true))
        assertEquals(true, shouldNotifyPlayerSurfaceExit(wasInside = true, isInside = false))
        assertEquals(false, shouldNotifyPlayerSurfaceExit(wasInside = true, isInside = true))
    }
}
