package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerLayoutTest {
    @Test
    fun `desktop mode starts at the approved 1024 dp breakpoint`() {
        assertEquals(PlayerWindowMode.Mobile, playerWindowMode(1023f))
        assertEquals(PlayerWindowMode.Desktop, playerWindowMode(1024f))
        assertEquals(PlayerWindowMode.Desktop, playerWindowMode(1920f))
    }
}
