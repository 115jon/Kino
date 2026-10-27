package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import kotlin.math.min

internal object LightNeutrals {
    val background = Color(0xFFF3F4EF)
    val surface = Color(0xFFFFFFFF)
    val card = Color(0xFFE7E9E2)
    val textPrimary = Color(0xFF171A15)
    val textSecondary = Color(0xFF3F463B)
    val textMuted = Color(0xFF545D4F)
    val borderDefault = Color(0xFFD3D8CC)
    val borderStrong = Color(0xFFA3AE9B)
}

private const val AccentTextContrast = 4.5f
private const val AccentGraphicContrast = 3f

internal fun Color.contrastAgainst(other: Color): Float {
    val first = luminance() + 0.05f
    val second = other.luminance() + 0.05f
    return max(first, second) / min(first, second)
}

internal fun Color.darkenedForContrast(background: Color, ratio: Float): Color {
    var step = 0
    var adjusted = this
    while (adjusted.contrastAgainst(background) < ratio && step < 20) {
        step++
        adjusted = lerp(this, Color.Black, step * 0.05f)
    }
    return adjusted
}

private fun foregroundFor(color: Color): Color =
    if (color.contrastAgainst(Color.White) >= color.contrastAgainst(LightNeutrals.textPrimary)) {
        Color.White
    } else {
        LightNeutrals.textPrimary
    }

fun ThemeColorPalette.toLightPalette(): ThemeColorPalette {
    val tint = secondary
    val background = lerp(LightNeutrals.background, tint, 0.03f)
    val card = lerp(LightNeutrals.card, tint, 0.05f)
    val accent = secondary.darkenedForContrast(card, AccentTextContrast)
    val accentVariant = secondaryVariant.darkenedForContrast(card, AccentGraphicContrast)
    return copy(
        secondary = accent,
        secondaryVariant = accentVariant,
        accentGradient = accentGradient.map { it.darkenedForContrast(background, AccentGraphicContrast) },
        nativeAccentHex = formatHexColor(accent.toArgb() and 0xFFFFFF),
        onSecondary = foregroundFor(accent),
        onSecondaryVariant = foregroundFor(accentVariant),
        focusRing = accent,
        focusBackground = lerp(LightNeutrals.surface, tint, 0.18f),
        background = background,
        backgroundElevated = lerp(LightNeutrals.surface, tint, 0.01f),
        backgroundCard = card,
    )
}
