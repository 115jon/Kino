package com.nuvio.app

import kotlin.test.Test
import kotlin.test.assertEquals

class AppLayoutTest {
    @Test
    fun `app shell uses mobile tablet and desktop width classes`() {
        assertEquals(AppNavigationMode.Mobile, appNavigationMode(767f))
        assertEquals(AppNavigationMode.Tablet, appNavigationMode(768f))
        assertEquals(AppNavigationMode.Tablet, appNavigationMode(1099f))
        assertEquals(AppNavigationMode.Desktop, appNavigationMode(1100f))
    }
}
