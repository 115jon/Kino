package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LightThemeContrastTest {
    private val customPresets = listOf(
        CustomThemeColors.Default,
        CustomThemeColors.solid(0xFFFFFF),
        CustomThemeColors.solid(0xFFEE00),
        CustomThemeColors.solid(0x101010),
    )

    private fun lightTokens(theme: AppTheme, custom: CustomThemeColors = CustomThemeColors.Default) =
        defaultNuvioThemeTokens(
            resolveNuvioPalette(theme, custom, darkTheme = false),
            amoled = false,
            colorScheme = null,
            darkTheme = false,
        ).colors

    private fun allLightTokens() =
        AppTheme.entries.filter { it != AppTheme.CUSTOM }.map { it.name to lightTokens(it) } +
            customPresets.map { "CUSTOM ${it.encode()}" to lightTokens(AppTheme.CUSTOM, it) }

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Float) {
        val ratio = foreground.compositeOver(background).contrastAgainst(background)
        assertTrue(ratio >= minimum, "$label contrast $ratio is below $minimum")
    }

    @Test
    fun textMeetsAaOnEverySurface() {
        allLightTokens().forEach { (name, colors) ->
            listOf(colors.background, colors.surface, colors.surfaceCard).forEach { surface ->
                assertContrast("$name textPrimary", colors.textPrimary, surface, 4.5f)
                assertContrast("$name textSecondary", colors.textSecondary, surface, 4.5f)
                assertContrast("$name textMuted", colors.textMuted, surface, 4.5f)
            }
        }
    }

    @Test
    fun accentMeetsAaAsTextAndAsFill() {
        allLightTokens().forEach { (name, colors) ->
            listOf(colors.background, colors.surface, colors.surfaceCard).forEach { surface ->
                assertContrast("$name accent", colors.accent, surface, 4.5f)
                assertContrast("$name focusRing", colors.focusRing, surface, 3f)
            }
            assertContrast("$name onAccent", colors.onAccent, colors.accent, 4.5f)
        }
    }

    @Test
    fun statusColorsMeetAaOnBackground() {
        allLightTokens().forEach { (name, colors) ->
            listOf(colors.success, colors.warning, colors.danger, colors.info).forEach {
                assertContrast("$name status", it, colors.background, 4.5f)
            }
        }
    }

    @Test
    fun playerChromeStaysDarkInLightMode() {
        val dark = defaultNuvioThemeTokens(ThemeColors.White, amoled = false, colorScheme = null).colors
        val light = lightTokens(AppTheme.WHITE)
        assertEquals(dark.playerControlsBackground, light.playerControlsBackground)
        assertEquals(dark.playerControlsForeground, light.playerControlsForeground)
        assertEquals(dark.playerTimelineTrack, light.playerTimelineTrack)
    }

    @Test
    fun chromeColorsFollowTheMode() {
        val light = nuvioChromeColors(AppTheme.WHITE, CustomThemeColors.Default, amoled = true, darkTheme = false)
        val dark = nuvioChromeColors(AppTheme.WHITE, CustomThemeColors.Default, amoled = true, darkTheme = true)
        assertFalse(light.darkTheme)
        assertTrue(light.background.contrastAgainst(Color.White) < 1.2f)
        assertEquals(Color.Black, dark.background)
        assertContrast("light chrome", light.foreground, light.background, 4.5f)
        assertContrast("dark chrome", dark.foreground, dark.background, 4.5f)
    }

    @Test
    fun themeModeResolvesAgainstSystem() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
        assertFalse(ThemeMode.LIGHT.isDark(systemDark = true))
        assertTrue(ThemeMode.DARK.isDark(systemDark = false))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromKey("bogus"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromKey("LIGHT"))
    }
}
