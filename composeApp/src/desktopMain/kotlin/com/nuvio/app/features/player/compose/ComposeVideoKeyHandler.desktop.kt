package com.nuvio.app.features.player.compose

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.nuvio.app.features.player.PlayerAudioLevel
import com.nuvio.app.features.player.WindowsMpvLibrary
import com.nuvio.app.features.player.WindowsPlayerWindowState
import com.nuvio.app.features.player.withMpv
import kotlin.math.roundToInt

internal class ComposeVideoKeyHandler(
    private val state: WindowsPlayerWindowState,
    private val panel: WindowsComposePlayerPanel,
) {
    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (state.startupStalled) {
            if (event.key == Key.R) panel.retryPlayback()
            return event.key == Key.R
        }
        when (event.key) {
            Key.Spacebar -> command("cycle", "pause")
            Key.DirectionLeft -> command("seek", "-10", "relative")
            Key.DirectionRight -> command("seek", "10", "relative")
            Key.DirectionUp -> adjustVolume(0.05f)
            Key.DirectionDown -> adjustVolume(-0.05f)
            Key.F -> panel.toggleFullScreen()
            Key.Escape -> return panel.exitFullScreen()
            else -> return false
        }
        return true
    }

    fun adjustVolume(delta: Float) {
        val level = (state.volumeLevel.fraction + delta).coerceIn(0f, 1f)
        val percent = (level * 100f).roundToInt()
        state.withMpv { ptr -> WindowsMpvLibrary.INSTANCE.mpv_set_property_string(ptr, "volume", percent.toString()) }
        state.volumeLevel = PlayerAudioLevel(fraction = level, isMuted = percent == 0)
    }

    private fun command(vararg args: String) {
        state.withMpv { ptr -> WindowsMpvLibrary.INSTANCE.mpv_command(ptr, arrayOf(*args, null)) }
    }
}
