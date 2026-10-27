package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object DesktopKeyCommands {
    private val escapeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val escape: SharedFlow<Unit> = escapeEvents

    fun dispatchUnconsumed(event: KeyEvent): Boolean {
        if (event.type == KeyEventType.KeyUp && event.key == Key.Escape) {
            escapeEvents.tryEmit(Unit)
        }
        return false
    }
}

@Composable
internal fun DesktopEscapeHandler(
    enabled: Boolean,
    onEscape: () -> Unit,
) {
    if (!isDesktopPlatform) return
    val active = LocalScreenActive.current
    val latestOnEscape = rememberUpdatedState(onEscape)
    LaunchedEffect(enabled, active) {
        if (!enabled || !active) return@LaunchedEffect
        DesktopKeyCommands.escape.collect { latestOnEscape.value() }
    }
}
