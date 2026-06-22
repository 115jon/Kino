package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val desktopPlaybackPrewarmStarted = AtomicBoolean(false)
private val desktopPlaybackPrewarmLog = Logger.withTag("DesktopPlayerTrace")

internal suspend fun prewarmDesktopPlaybackBackend() {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    if (osName.contains("windows")) {
        withContext(Dispatchers.IO) {
            runCatching { WindowsJoglNativeLoader.ensureConfigured() }
                .onFailure { desktopPlaybackPrewarmLog.w(it) { "windows jogl preload failed" } }
        }
        return
    }
    if (!osName.contains("mac")) return
    if (!desktopPlaybackPrewarmStarted.compareAndSet(false, true)) return

    val bridge = withContext(Dispatchers.IO) {
        runCatching { MacOSMPVBridgeLib.INSTANCE }.getOrNull()
    } ?: return

    delay(1_500)

    withContext(Dispatchers.IO) {
        runCatching { bridge.nuvio_player_prewarm() }
    }
}
