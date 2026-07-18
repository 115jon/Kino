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
import java.awt.Dimension
import java.awt.Color as AwtColor

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

fun main() {
    configureWindowsAppUserModelId()
    configureMacOsNativeAppearance()
    System.setProperty("java.net.preferIPv4Stack", "true")
    configureComposeInterop()
    initializeDesktopAppLogging()
    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(
            state = windowState,
            onCloseRequest = ::exitApplication,
            title = "Kino",
            icon = painterResource(Res.drawable.app_logo),
        ) {
            DisposableEffect(window) {
                window.minimumSize = Dimension(960, 640)
                window.background = DesktopWindowBackground
                window.contentPane.background = DesktopWindowBackground
                window.rootPane.background = DesktopWindowBackground
                onDispose { }
            }

            LaunchedEffect(Unit) {
                prewarmDesktopPlaybackBackend()
            }

            App()
        }
    }
}
