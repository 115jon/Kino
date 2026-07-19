package com.nuvio.app.features.player

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

internal fun Color.toMpvArgbColor(): String {
    fun component(value: Float): String =
        (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0').uppercase()

    return "#${component(alpha)}${component(red)}${component(green)}${component(blue)}"
}

internal fun SubtitleStyleState.toMpvOverrideMode(): String = "force"

internal fun SubtitleStyleState.toMpvPosition(): String =
    (100 - bottomOffset).coerceIn(0, 100).toString()

internal fun SubtitleStyleState.toMpvOutlineSize(): String =
    if (outlineEnabled) {
        outlineWidth.coerceIn(0, 20).toFloat().toString()
    } else {
        "0.0"
    }

internal fun SubtitleStyleState.toMpvBorderStyle(): String =
    if (outlineEnabled || backgroundColor.alpha <= 0f) {
        "outline-and-shadow"
    } else {
        "opaque-box"
    }
