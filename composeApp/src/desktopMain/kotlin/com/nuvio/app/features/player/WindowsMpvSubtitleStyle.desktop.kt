package com.nuvio.app.features.player

import com.sun.jna.Pointer

internal fun applyWindowsMpvSubtitleStyle(ptr: Pointer, style: SubtitleStyleState) {
    val lib = WindowsMpvLibrary.INSTANCE
    val properties = listOf(
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
    desktopPlayerTrace(
        "applying subtitle style text=${style.textColor.toMpvArgbColor()} " +
            "background=${style.backgroundColor.toMpvArgbColor()} " +
            "outline=${style.outlineColor.toMpvArgbColor()} " +
            "outlineSize=${style.toMpvOutlineSize()} fontSize=${style.fontSizeSp} " +
            "bold=${style.bold} position=${style.toMpvPosition()} override=${style.toMpvOverrideMode()}"
    )
    properties.forEach { (name, value) ->
        val result = lib.mpv_set_property_string(ptr, name, value)
        if (result < 0) {
            desktopPlayerTrace("subtitle style property rejected name=$name value=$value result=$result")
        }
    }

}

internal fun reapplyWindowsMpvSubtitleStyleLater(
    state: WindowsPlayerWindowState,
    ptr: Pointer,
) {
    val generation = state.sourceGeneration
    scheduleWindowsSwingAction(300) {
        state.withMpv { currentPtr ->
            if (currentPtr == ptr && state.sourceGeneration == generation) {
                applyWindowsMpvSubtitleStyle(currentPtr, state.subtitleStyle)
            }
        }
    }
}
