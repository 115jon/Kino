package com.nuvio.app.features.player.compose

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.nuvio.app.features.player.WindowsPlayerWindowState

internal class ComposeVideoKeyHandler(
    private val state: WindowsPlayerWindowState,
    private val panel: WindowsComposePlayerPanel,
) {
    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (state.playback.startupStalled) {
            if (event.key == Key.R) panel.retryPlayback()
            return event.key == Key.R
        }
        val engine = state.engine
        when (event.key) {
            Key.Spacebar -> engine.togglePause()
            Key.DirectionLeft -> engine.seekBy(-10_000L)
            Key.DirectionRight -> engine.seekBy(10_000L)
            Key.DirectionUp -> adjustVolume(0.05f)
            Key.DirectionDown -> adjustVolume(-0.05f)
            Key.F -> panel.toggleFullScreen()
            Key.Escape -> return panel.exitFullScreen()
            else -> return false
        }
        return true
    }

    fun adjustVolume(delta: Float) {
        state.engine.setVolumeLevelNow((state.playback.volumeLevel.fraction + delta).coerceIn(0f, 1f))
    }
}
