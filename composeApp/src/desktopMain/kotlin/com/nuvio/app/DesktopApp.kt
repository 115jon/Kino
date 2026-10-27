package com.nuvio.app

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import co.touchlab.kermit.Logger
import com.nuvio.app.core.logging.initializeDesktopAppLogging
import com.nuvio.app.core.ui.DesktopKeyCommands
import com.nuvio.app.features.player.prewarmDesktopPlaybackBackend
import com.nuvio.app.features.player.compose.configureWindowsComposeVideoRenderApi
import com.nuvio.app.features.player.compose.configureWindowsVideoSurfaceSelection
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_logo
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.Frame
import java.awt.Color as AwtColor
import java.awt.EventQueue

private val DesktopWindowBackground = AwtColor(0x0C, 0x0C, 0x0C)
private val DesktopMinimumInteractiveSize = 40.dp
private val singleInstanceLogger = Logger.withTag("DesktopSingleInstance")

internal fun restoredDesktopWindowState(extendedState: Int): Int =
    extendedState and Frame.ICONIFIED.inv()

internal fun shouldStartDesktopApplication(instanceLaunch: DesktopInstanceLaunch?): Boolean =
    instanceLaunch == null || instanceLaunch is DesktopInstanceLaunch.Primary

private fun activateDesktopWindow(window: ComposeWindow) {
    EventQueue.invokeLater {
        if (!window.isDisplayable) return@invokeLater
        if (!window.isVisible) window.isVisible = true
        window.extendedState = restoredDesktopWindowState(window.extendedState)
        window.toFront()
        window.requestFocus()
    }
}

private fun configureMacOsNativeAppearance() {
    val osName = System.getProperty("os.name")?.lowercase() ?: return
    if (!osName.contains("mac")) return
    System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua")
}

private fun configureComposeInterop() {
    val blendingOverride = System.getProperty("kino.compose-interop-blending")
    System.setProperty(
        "compose.interop.blending",
        blendingOverride ?: (!isWindowsPlatform()).toString(),
    )
}

private fun configureWindowsPresentationCompatibility() {
    if (!isWindowsPlatform()) return
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
    configureWindowsVideoSurfaceSelection()
    configureComposeInterop()
    configureWindowsPresentationCompatibility()
    configureWindowsComposeVideoRenderApi()
    initializeDesktopAppLogging()
    val activationRelay = DesktopActivationRelay()
    val instanceLaunch = if (isWindowsPlatform()) {
        DesktopSingleInstanceCoordinator.acquire(onActivation = activationRelay::requestActivation)
    } else {
        null
    }
    when (instanceLaunch) {
        is DesktopInstanceLaunch.Secondary -> {
            if (instanceLaunch.activationDelivered) {
                singleInstanceLogger.i { "Kino is already running; activated the existing window" }
            } else {
                singleInstanceLogger.w { "Kino is already running, but its window could not be activated" }
            }
        }
        is DesktopInstanceLaunch.Unavailable -> {
            singleInstanceLogger.e { "Single-instance coordination is unavailable: ${instanceLaunch.reason}" }
        }
        else -> Unit
    }
    if (!shouldStartDesktopApplication(instanceLaunch)) return
    val instanceCoordinator = (instanceLaunch as? DesktopInstanceLaunch.Primary)?.coordinator

    try {
        application {
            val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)
            Window(
                state = windowState,
                onCloseRequest = ::exitApplication,
                title = "Kino",
                icon = painterResource(Res.drawable.app_logo),
                onKeyEvent = DesktopKeyCommands::dispatchUnconsumed,
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
                    val activationSubscription = activationRelay.attach {
                        activateDesktopWindow(window)
                    }
                    val vrrCompatibility = installWindowsVrrCompatibility(window)
                    val compositorClockPacing = installWindowsCompositorClockPacing(window)
                    window.minimumSize = Dimension(960, 640)
                    window.background = DesktopWindowBackground
                    window.contentPane.background = DesktopWindowBackground
                    window.rootPane.background = DesktopWindowBackground
                    onDispose {
                        activationSubscription.close()
                        vrrCompatibility?.close()
                        compositorClockPacing?.close()
                    }
                }

                LaunchedEffect(Unit) {
                    prewarmDesktopPlaybackBackend()
                }

                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides DesktopMinimumInteractiveSize) {
                    App()
                }
            }
        }
    } finally {
        instanceCoordinator?.close()
    }
}
