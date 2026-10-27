package com.nuvio.app.features.player.harness

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.player.MpvUrlResolver
import com.nuvio.app.features.player.WindowsMpvPlayerBackend
import com.nuvio.app.features.player.WindowsResolvedPlaybackUrls
import com.nuvio.app.features.player.compose.configureWindowsComposeVideoRenderApi
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.lang.management.ManagementFactory
import javax.imageio.ImageIO
import java.time.LocalTime
import kotlinx.coroutines.delay

private object HarnessLogWriter : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        println("${LocalTime.now()} $tag $message")
        throwable?.printStackTrace(System.out)
    }
}

private val localFileResolver = MpvUrlResolver { url, audioUrl, headers, _ ->
    WindowsResolvedPlaybackUrls(url, audioUrl, headers, headers)
}

private fun harnessLog(message: String) {
    println("${LocalTime.now()} harness $message")
}

private class CpuMeter {
    private val bean = ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
    private var cpuNs = bean.processCpuTime
    private var wallNs = System.nanoTime()

    fun sample(label: String) {
        val nextCpu = bean.processCpuTime
        val nextWall = System.nanoTime()
        val percent = (nextCpu - cpuNs) * 100.0 / (nextWall - wallNs)
        harnessLog("cpu label=$label percentOfCore=${"%.1f".format(percent)} seconds=${"%.1f".format((nextWall - wallNs) / 1e9)}")
        cpuNs = nextCpu
        wallNs = nextWall
    }
}

fun main() {
    val surface = System.getProperty("kino.harness.surface") ?: "compose"
    val media = System.getProperty("kino.harness.media").orEmpty().split(';').filter { it.isNotBlank() }
    val script = System.getProperty("kino.harness.script").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    require(media.isNotEmpty()) { "kino.harness.media is required" }
    System.setProperty("kino.windows.video-surface", surface)
    System.setProperty("compose.interop.blending", (surface in setOf("embedded", "gl", "opengl")).toString())
    if (System.getProperty("skiko.vsync.enabled") == null) System.setProperty("skiko.vsync.enabled", "true")
    if (System.getProperty("skiko.rendering.windows.waitForFrameVsyncOnRedrawImmediately") == null) {
        System.setProperty("skiko.rendering.windows.waitForFrameVsyncOnRedrawImmediately", "true")
    }
    if (System.getProperty("kino.player.perf-log") == null) System.setProperty("kino.player.perf-log", "true")
    if (System.getProperty("kino.player.perf-interval-ms") == null) System.setProperty("kino.player.perf-interval-ms", "1000")
    configureWindowsComposeVideoRenderApi()
    Logger.setLogWriters(listOf(HarnessLogWriter))
    harnessLog("start surface=$surface media=${media.size} script=$script")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kino player harness",
            focusable = false,
            alwaysOnTop = true,
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            val backend = remember { WindowsMpvPlayerBackend(localFileResolver) }
            var index by remember { mutableIntStateOf(0) }
            var controller by remember { mutableStateOf<PlayerEngineController?>(null) }
            var lastPlaying by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                backend.PlayerSurface(
                    sourceUrl = media[index],
                    sourceAudioUrl = null,
                    sourceHeaders = emptyMap(),
                    sourceResponseHeaders = emptyMap(),
                    externalSubtitles = emptyList(),
                    streamType = null,
                    useYoutubeChunkedPlayback = false,
                    modifier = Modifier.fillMaxSize(),
                    playWhenReady = true,
                    resizeMode = PlayerResizeMode.Fit,
                    useNativeController = false,
                    initialPositionMs = null,
                    initialPositionRequestKey = null,
                    onInitialPositionHandled = { _, _ -> },
                    overlayContent = {},
                    onControllerReady = { controller = it },
                    onSnapshot = { snapshot ->
                        if (snapshot.isPlaying != lastPlaying) {
                            lastPlaying = snapshot.isPlaying
                            harnessLog("playing=${snapshot.isPlaying} positionMs=${snapshot.positionMs}")
                        }
                    },
                    onError = { error -> if (error != null) harnessLog("error $error") },
                    onSurfaceInteraction = {},
                    onSurfaceExit = {},
                    onWindowFocusChanged = { _, _ -> },
                )
            }
            LaunchedEffect(controller != null) {
                val active = controller ?: return@LaunchedEffect
                val cpu = CpuMeter()
                script.forEach { step ->
                    val argument = step.drop(step.indexOfFirst { it.isDigit() || it == '-' }.takeIf { it >= 0 } ?: step.length)
                    when {
                        step.startsWith("wait") -> delay((argument.toDouble() * 1000).toLong())
                        step.startsWith("cpu") -> cpu.sample(argument.ifEmpty { "window" })
                        step.startsWith("reset") -> cpu.sample("discard")
                        step.startsWith("seekby") -> {
                            harnessLog("action seekby offsetMs=$argument")
                            active.seekBy(argument.toLong())
                        }
                        step.startsWith("seek") -> {
                            harnessLog("action seek targetMs=$argument")
                            active.seekTo(argument.toLong())
                        }
                        step == "next" -> {
                            index = (index + 1) % media.size
                            harnessLog("action next index=$index")
                        }
                        step.startsWith("shot") -> {
                            val bounds = window.contentPane.let { pane ->
                                val origin = pane.locationOnScreen
                                Rectangle(origin.x, origin.y, pane.width, pane.height)
                            }
                            val target = File(System.getProperty("kino.harness.shots", "."), "${step.removePrefix("shot")}.png")
                            ImageIO.write(Robot().createScreenCapture(bounds), "png", target)
                            harnessLog("shot ${target.absolutePath}")
                        }
                        step == "pause" -> active.pause()
                        step == "play" -> active.play()
                        step == "exit" -> {
                            harnessLog("action exit")
                            exitApplication()
                        }
                    }
                }
            }
        }
    }
}
