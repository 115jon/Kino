package com.nuvio.app.features.player.harness

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nuvio.app.App
import com.nuvio.app.AppScreenTab
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.compose.configureWindowsComposeVideoRenderApi
import com.nuvio.app.navigation.PlayerRoute
import java.awt.Component
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlinx.coroutines.delay

internal fun runPlayerRuntimeHarness(media: String, script: List<String>) {
    System.setProperty("kino.windows.video-surface", System.getProperty("kino.harness.surface") ?: "compose")
    configureWindowsComposeVideoRenderApi()
    val route = PlayerRoute(
        PlayerLaunchStore.put(
            PlayerLaunch(
                profileId = 1,
                title = "Harness",
                sourceUrl = media,
                streamTitle = "Harness",
                providerName = "Local",
                parentMetaId = "harness",
                parentMetaType = "movie",
                initialPositionMs = System.getProperty("kino.harness.startMs")?.toLongOrNull() ?: 0L,
            ),
        ),
    )
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kino runtime harness",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            LaunchedEffect(Unit) {
                RuntimeHarnessDriver(window).run(script)
                exitApplication()
            }
            App(initialTab = AppScreenTab.Home, initialRoute = route, bypassAppGate = true)
        }
    }
}

private class RuntimeHarnessDriver(private val window: ComposeWindow) {
    private val focus = HarnessFocusDriver(window)
    private val target: Component get() = window.contentPane.getComponent(0)

    suspend fun run(script: List<String>) {
        script.forEach { step ->
            val argument = step.dropWhile { !it.isDigit() && it != '-' }
            harnessLog("runtime step=$step")
            when {
                step.startsWith("wait") -> delay((argument.toDouble() * 1000).toLong())
                step.startsWith("alttab") -> repeat(argument.toInt()) { altTab() }
                step.startsWith("mincycle") -> focus.cycle(argument.toInt(), 1_500L, 3_000L, minimize = true)
                step == "hover" -> onEdt { move(0.5, 0.5) }
                step.startsWith("clicktimeline") -> onEdt { click(argument.toDouble() / 100.0, timelineY()) }
                step.startsWith("key") -> onEdt { key(argument.toInt()) }
            }
        }
        focus.dispose()
    }

    private suspend fun altTab() {
        onEdt { keyEvent(KeyEvent.KEY_PRESSED, KeyEvent.VK_ALT, KeyEvent.ALT_DOWN_MASK) }
        focus.blur()
        delay(1_500L)
        focus.focus()
        delay(150L)
        onEdt {
            keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_TAB, KeyEvent.ALT_DOWN_MASK)
            keyEvent(KeyEvent.KEY_RELEASED, KeyEvent.VK_ALT, 0)
        }
        delay(3_000L)
    }

    private fun timelineY(): Double = System.getProperty("kino.harness.timelineY")?.toDoubleOrNull() ?: 0.885

    private fun point(fx: Double, fy: Double): Pair<Int, Int> =
        (target.width * fx).toInt() to (target.height * fy).toInt()

    private fun move(fx: Double, fy: Double) {
        val (x, y) = point(fx, fy)
        mouse(MouseEvent.MOUSE_MOVED, x, y, 0, 0)
    }

    private fun click(fx: Double, fy: Double) {
        val (x, y) = point(fx, fy)
        harnessLog("runtime click x=$x y=$y")
        mouse(MouseEvent.MOUSE_MOVED, x, y, 0, 0)
        mouse(MouseEvent.MOUSE_PRESSED, x, y, MouseEvent.BUTTON1_DOWN_MASK, 1)
        mouse(MouseEvent.MOUSE_RELEASED, x, y, 0, 1)
        mouse(MouseEvent.MOUSE_CLICKED, x, y, 0, 1)
    }

    private fun mouse(id: Int, x: Int, y: Int, modifiers: Int, clicks: Int) {
        val button = if (id == MouseEvent.MOUSE_MOVED) MouseEvent.NOBUTTON else MouseEvent.BUTTON1
        target.dispatchEvent(MouseEvent(target, id, System.currentTimeMillis(), modifiers, x, y, clicks, false, button))
    }

    private fun key(code: Int) {
        keyEvent(KeyEvent.KEY_PRESSED, code, 0)
        keyEvent(KeyEvent.KEY_RELEASED, code, 0)
    }

    private fun keyEvent(id: Int, code: Int, modifiers: Int) {
        val focusOwner = window.mostRecentFocusOwner ?: target
        focusOwner.dispatchEvent(KeyEvent(focusOwner, id, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED))
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeAndWait(block)
    }
}
