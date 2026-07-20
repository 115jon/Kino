package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsPlayerInteractionPolicyTest {
    @Test
    fun `overlay pointer counts as inside the player`() {
        assertEquals(true, isWindowsPlayerPointerInside(playerInside = false, overlayInside = true))
        assertEquals(true, isWindowsPlayerPointerInside(playerInside = true, overlayInside = false))
        assertEquals(false, isWindowsPlayerPointerInside(playerInside = false, overlayInside = false))
    }

    @Test
    fun `controls hide only after pointer leaves the full player surface`() {
        assertEquals(false, shouldHideWindowsPlayerControls(pointerInside = true))
        assertEquals(true, shouldHideWindowsPlayerControls(pointerInside = false))
    }
}
