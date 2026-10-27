package com.nuvio.app.features.player

internal fun mpvSubtitleStyleProperties(style: SubtitleStyleState): List<Pair<String, String>> = listOf(
    "sub-ass-override" to style.toMpvOverrideMode(),
    "sub-color" to style.textColor.toMpvArgbColor(),
    "sub-back-color" to style.backgroundColor.toMpvArgbColor(),
    "sub-outline-color" to style.outlineColor.toMpvArgbColor(),
    "sub-border-style" to style.toMpvBorderStyle(),
    "sub-outline-size" to style.toMpvOutlineSize(),
    "sub-font-size" to style.fontSizeSp.toString(),
    "sub-bold" to if (style.bold) "yes" else "no",
    "sub-pos" to style.toMpvPosition(),
)

internal fun applyWindowsMpvSubtitleStyle(client: MpvClient, style: SubtitleStyleState) {
    desktopPlayerTrace(
        "applying subtitle style text=${style.textColor.toMpvArgbColor()} " +
            "background=${style.backgroundColor.toMpvArgbColor()} " +
            "outline=${style.outlineColor.toMpvArgbColor()} " +
            "outlineSize=${style.toMpvOutlineSize()} fontSize=${style.fontSizeSp} " +
            "bold=${style.bold} position=${style.toMpvPosition()} override=${style.toMpvOverrideMode()}"
    )
    mpvSubtitleStyleProperties(style).forEach { (name, value) ->
        val result = client.setProperty(name, value)
        if (result < 0) {
            desktopPlayerTrace("subtitle style property rejected name=$name value=$value result=$result")
        }
    }
}
