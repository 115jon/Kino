package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeHeroSectionTest {

    @Test
    fun `mobile hero height follows viewport height when provided`() {
        val layout = homeHeroLayout(
            maxWidthDp = 390f,
            viewportHeightDp = 844f,
        )

        assertEquals(false, layout.isTablet)
        assertEquals(692.08f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `tablet hero height is viewport driven when viewport height is provided`() {
        val layout = homeHeroLayout(
            maxWidthDp = 840f,
            viewportHeightDp = 1200f,
        )

        assertEquals(true, layout.isTablet)
        assertEquals(580.0f, layout.heroHeight.value, 0.001f)
    }

    @Test
    fun `tablet hero height uses min limit when viewport height is not provided`() {
        val layout = homeHeroLayout(
            maxWidthDp = 840f,
            viewportHeightDp = null,
        )

        assertEquals(true, layout.isTablet)
        assertEquals(460.0f, layout.heroHeight.value, 0.001f)
    }
}