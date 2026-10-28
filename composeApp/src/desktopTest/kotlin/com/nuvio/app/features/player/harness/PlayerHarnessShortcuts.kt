package com.nuvio.app.features.player.harness

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.DesktopPlayerToolCluster
import com.nuvio.app.features.player.PlayerAudioLevel
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerFullscreenHint
import com.nuvio.app.features.player.PlayerShortcut
import com.nuvio.app.features.player.PlayerShortcutHelp
import com.nuvio.app.features.player.rememberPlayerFullscreen
import java.awt.Window
import java.awt.event.KeyEvent

private val harnessShortcutHelp = mutableStateOf(false)

@Composable
internal fun HarnessOverlay(controller: PlayerEngineController?) {
    val isFullscreen = rememberPlayerFullscreen(controller)
    Box(Modifier.fillMaxSize()) {
        PlayerFullscreenHint(
            isFullscreen = isFullscreen,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
        )
        DesktopPlayerToolCluster(
            volumeLevel = PlayerAudioLevel(1f, false),
            showVolumeControl = false,
            onVolumeChanged = {},
            showFullscreenControl = true,
            isFullscreen = isFullscreen,
            onFullscreenClick = { controller?.toggleFullscreen() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        )
        PlayerShortcutHelp(
            visible = harnessShortcutHelp.value,
            onDismiss = { harnessShortcutHelp.value = false },
        )
    }
}

internal fun handleHarnessShortcut(controller: PlayerEngineController, shortcut: PlayerShortcut): Boolean {
    harnessLog("shortcut $shortcut fullscreen=${controller.fullscreen.value}")
    when (shortcut) {
        PlayerShortcut.ToggleFullscreen -> controller.toggleFullscreen()
        PlayerShortcut.Back -> if (controller.fullscreen.value) controller.exitFullscreen() else harnessLog("back leaves player")
        PlayerShortcut.TogglePlayback -> controller.togglePlayPause()
        is PlayerShortcut.Seek -> controller.seekBy(shortcut.offsetMs)
        PlayerShortcut.ShortcutHelp -> harnessShortcutHelp.value = !harnessShortcutHelp.value
        else -> Unit
    }
    return true
}

internal fun pressKey(window: Window, keyCode: Int, modifiers: Int = 0) {
    val now = System.currentTimeMillis()
    window.dispatchEvent(KeyEvent(window, KeyEvent.KEY_PRESSED, now, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED))
    window.dispatchEvent(KeyEvent(window, KeyEvent.KEY_RELEASED, now, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED))
}
