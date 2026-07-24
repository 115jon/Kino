package com.nuvio.app

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nuvio.app.core.logging.initializeDesktopAppLogging
import com.nuvio.app.features.player.prewarmDesktopPlaybackBackend
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_logo
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.Color as AwtColor
import java.awt.EventQueue

private val DesktopWindowBackground = AwtColor(0x0C, 0x0C, 0x0C)

private fun configureMacOsNativeAppearance() {
    val osName = System.getProperty("os.name")?.lowercase() ?: return
    if (!osName.contains("mac")) return
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
}

private fun configureComposeInterop() {
    val osName = System.getProperty("os.name")?.lowercase().orEmpty()
    val windowsVideoSurface = System.getProperty("kino.windows.video-surface")?.trim()?.lowercase()
    val usesNativeWindowsVideo = osName.contains("win") && windowsVideoSurface !in setOf("embedded", "gl", "opengl")
    val blendingOverride = System.getProperty("kino.compose-interop-blending")
    System.setProperty(
        "compose.interop.blending",
        blendingOverride ?: (!usesNativeWindowsVideo).toString(),
    )
}

private fun configureWindowsPresentationCompatibility() {
    val osName = System.getProperty("os.name")?.lowercase().orEmpty()
    if (!osName.contains("win")) return
    if (System.getProperty("skiko.vsync.enabled") == null) {
        System.setProperty("skiko.vsync.enabled", "true")
    }
    if (System.getProperty("skiko.rendering.windows.waitForFrameVsyncOnRedrawImmediately") == null) {
        System.setProperty("skiko.rendering.windows.waitForFrameVsyncOnRedrawImmediately", "true")
    }
}

fun main() {
    configureWindowsAppUserModelId()
    configureWindowsCaptureCompatibility()
    configureMacOsNativeAppearance()
    System.setProperty("java.net.preferIPv4Stack", "true")
    configureComposeInterop()
    configureWindowsPresentationCompatibility()
    initializeDesktopAppLogging()
    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(
            state = windowState,
            onCloseRequest = ::exitApplication,
            title = "Kino",
            icon = painterResource(Res.drawable.app_logo),
        ) {
            LaunchedEffect(window) {
                if (!isWindowsPlatform()) return@LaunchedEffect
                var subscription: AutoCloseable? = null
                try {
                    val initialDarkMode = withContext(Dispatchers.IO) { readWindowsDarkMode() }
                    applyWindowsTitleBarTheme(window, initialDarkMode)
                    subscription = subscribeToWindowsThemeChanges {
                        val darkMode = readWindowsDarkMode()
                        EventQueue.invokeLater {
                            if (window.isDisplayable) {
                                applyWindowsTitleBarTheme(window, darkMode)
                            }
                        }
                    }
                    awaitCancellation()
                } finally {
                    subscription?.close()
                }
            }

            DisposableEffect(window) {
                val vrrCompatibility = installWindowsVrrCompatibility(window)
                window.minimumSize = Dimension(960, 640)
                window.background = DesktopWindowBackground
                window.contentPane.background = DesktopWindowBackground
                window.rootPane.background = DesktopWindowBackground
                onDispose { vrrCompatibility?.close() }
            }

            LaunchedEffect(Unit) {
                prewarmDesktopPlaybackBackend()
            }

            App()
        }
    }
}
