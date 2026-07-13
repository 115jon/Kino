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

    @Test
    fun `desktop platform keeps desktop controls available at usable scaled widths`() {
        assertEquals(PlayerWindowMode.Mobile, playerWindowMode(767f, desktopPlatform = false))
        assertEquals(PlayerWindowMode.Desktop, playerWindowMode(768f, desktopPlatform = true))
    }
}
