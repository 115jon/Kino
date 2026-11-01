package com.nuvio.app.features.player.harness

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import java.awt.Frame
import java.awt.Window
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlinx.coroutines.delay

internal val harnessWindowFocusable: Boolean = System.getProperty("kino.harness.focusable") == "true"

private const val BackwardsJumpThresholdMs = 750L
private const val SeekGraceMs = 3_000L

internal class HarnessPositionMonitor {
    private var lastPositionMs = -1L
    private var lastSeekAtMs = 0L
    var backwardsJumps = 0
        private set

    fun markSeek() {
        lastSeekAtMs = System.currentTimeMillis()
    }

    fun observe(snapshot: PlayerPlaybackSnapshot) {
        val position = snapshot.positionMs
        val previous = lastPositionMs
        lastPositionMs = position
        if (previous < 0L || position >= previous - BackwardsJumpThresholdMs) return
        val sinceSeekMs = System.currentTimeMillis() - lastSeekAtMs
        if (sinceSeekMs < SeekGraceMs) return
        backwardsJumps += 1
        harnessLog("position-backwards fromMs=$previous toMs=$position deltaMs=${position - previous} count=$backwardsJumps")
    }
}

internal class HarnessFocusDriver(private val window: Window) {
    private var decoy: JFrame? = null

    fun blur() = onEdt {
        val other = decoy ?: JFrame("Kino harness decoy").apply {
            setSize(320, 200)
            setLocation(40, 40)
            decoy = this
        }
        other.isVisible = true
        other.toFront()
        other.requestFocus()
        harnessLog("focus action=blur playerFocused=${window.isFocused}")
    }

    fun focus() = onEdt {
        window.toFront()
        window.requestFocus()
        harnessLog("focus action=focus playerFocused=${window.isFocused}")
    }

    fun minimize() = onEdt {
        (window as? Frame)?.extendedState = Frame.ICONIFIED
        harnessLog("focus action=minimize")
    }

    fun restore() = onEdt {
        (window as? Frame)?.extendedState = Frame.NORMAL
        window.toFront()
        harnessLog("focus action=restore")
    }

    suspend fun cycle(count: Int, awayMs: Long, backMs: Long, minimize: Boolean) {
        repeat(count) { index ->
            harnessLog("focus cycle index=$index minimize=$minimize")
            if (minimize) minimize() else blur()
            delay(awayMs)
            if (minimize) restore() else focus()
            delay(backMs)
        }
    }

    fun dispose() = onEdt { decoy?.dispose() }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }
}
