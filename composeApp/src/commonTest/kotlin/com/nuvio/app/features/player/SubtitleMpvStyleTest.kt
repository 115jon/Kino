package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleMpvStyleTest {
    @Test
    fun `desktop mpv style mapping preserves rgba and forces ass overrides`() {
        val style = SubtitleStyleState(
            textColor = Color(0xFFFFD700),
            backgroundColor = Color.Transparent,
            outlineColor = Color.Black,
            outlineEnabled = true,
            outlineWidth = 3,
            fontSizeSp = 18,
            bottomOffset = 20,
        )

        assertEquals("#FFFFD700", style.textColor.toMpvArgbColor())
        assertEquals("force", style.toMpvOverrideMode())
        assertEquals("80", style.toMpvPosition())
        assertEquals("3.0", style.toMpvOutlineSize())
    }
}
