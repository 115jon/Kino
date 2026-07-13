package com.nuvio.app.features.details

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailSeriesLayoutTest {
    @Test
    fun `desktop series selector is platform aware and width constrained`() {
        assertFalse(detailUsesDesktopSeasonRail(1099f, desktopPlatform = false))
        assertTrue(detailUsesDesktopSeasonRail(768f, desktopPlatform = true))
        assertTrue(detailUsesDesktopSeasonRail(1100f, desktopPlatform = false))
    }
}
