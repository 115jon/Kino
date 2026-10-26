package com.nuvio.app.features.player.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import com.nuvio.app.features.player.DesktopBackendPerfCollector
import com.nuvio.app.features.player.WindowsPlayerWindowState

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun WindowsComposeVideoSurface(
    state: WindowsPlayerWindowState,
    modifier: Modifier,
    overlayContent: @Composable () -> Unit,
    onInitializationError: (String) -> Unit,
    onPlayerStateChanged: () -> Unit,
    onSurfaceInteraction: (Boolean) -> Unit,
    onSurfaceExit: () -> Unit,
    onWindowFocusChanged: (Boolean, Long?) -> Unit,
    perfCollector: DesktopBackendPerfCollector,
) {
    val window = LocalAwtWindow.current
    val currentOnSurfaceInteraction = rememberUpdatedState(onSurfaceInteraction)
    val currentOnSurfaceExit = rememberUpdatedState(onSurfaceExit)
    val panel = remember {
        WindowsComposePlayerPanel(
            state = state,
            onInitializationError = onInitializationError,
            onPlayerStateChanged = onPlayerStateChanged,
            onWindowFocusChanged = { focused, handle ->
                if (!focused) currentOnSurfaceExit.value()
                onWindowFocusChanged(focused, handle)
            },
            perfCollector = perfCollector,
        )
    }
    val focusRequester = remember { FocusRequester() }
    val keyHandler = remember(panel) { ComposeVideoKeyHandler(state, panel) }
    DisposableEffect(panel, window) {
        window?.let(panel::start)
        panel.focusRequest = { runCatching { focusRequester.requestFocus() } }
        onDispose { panel.focusRequest = {} }
    }
    LaunchedEffect(panel) {
        runCatching { focusRequester.requestFocus() }
    }
    Box(
        modifier = modifier
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent(keyHandler::handle)
            .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) {
                currentOnSurfaceInteraction.value(false)
            }
            .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) {
                currentOnSurfaceExit.value()
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                if (event.changes.any { it.isConsumed }) return@onPointerEvent
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
                keyHandler.adjustVolume(-delta * 0.02f)
            },
    ) {
        DesktopVideoLayer(
            renderer = panel.renderer,
            stats = panel.stats,
            visible = panel.videoVisible,
            modifier = Modifier.fillMaxSize(),
        )
        overlayContent()
    }
}
