package com.nuvio.app.harness

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nuvio.app.installWindowsCompositorClockPacing
import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.time.LocalTime
import java.util.Collections
import javax.swing.SwingUtilities
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Image as SkiaImage

private const val PosterCount = 240
private const val RowCount = 30
private const val CardsPerRow = 24

private fun harnessLog(message: String) {
    println("${LocalTime.now()} harness $message")
}

private fun poster(index: Int): ImageBitmap {
    val random = Random(index)
    val bitmap = Bitmap().apply { allocN32Pixels(300, 450) }
    val canvas = Canvas(bitmap)
    canvas.drawPaint(Paint().apply { color = (0xFF000000 or random.nextLong(0xFFFFFF)).toInt() })
    repeat(40) {
        canvas.drawCircle(
            random.nextFloat() * 300f,
            random.nextFloat() * 450f,
            8f + random.nextFloat() * 40f,
            Paint().apply { color = (0x60000000 or random.nextInt(0xFFFFFF)) },
        )
    }
    canvas.drawString("Title $index", 16f, 420f, Font(null, 28f), Paint().apply { color = -1 })
    return SkiaImage.makeFromBitmap(bitmap.apply { setImmutable() }).toComposeImageBitmap()
}

private class EdtLagProbe {
    private val lags = Collections.synchronizedList(ArrayList<Double>(4096))
    private val thread = Thread {
        while (!Thread.currentThread().isInterrupted) {
            val postedNs = System.nanoTime()
            SwingUtilities.invokeLater { lags += (System.nanoTime() - postedNs) / 1_000_000.0 }
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
                return@Thread
            }
        }
    }.apply { isDaemon = true }

    fun start() = thread.start()

    fun stop(): String {
        thread.interrupt()
        val sorted = synchronized(lags) { lags.sorted() }
        fun percentile(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()]
        return "edtLagP50=${"%.1f".format(percentile(0.5))} edtLagP95=${"%.1f".format(percentile(0.95))} " +
            "edtLagMax=${"%.1f".format(sorted.lastOrNull() ?: 0.0)}"
    }
}

private class FrameRecorder {
    private val intervals = ArrayList<Double>(2048)
    private var last = 0L
    private val cpu = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
    private val cpuStartNs = cpu.processCpuTime
    private val probe = EdtLagProbe().also { it.start() }

    fun record(frameNanos: Long) {
        if (last != 0L) intervals += (frameNanos - last) / 1_000_000.0
        last = frameNanos
    }

    fun drain(pass: Int, seconds: Double) {
        val cpuPercent = (cpu.processCpuTime - cpuStartNs) / 1e7 / seconds
        val lag = probe.stop()
        val sorted = intervals.sorted()
        fun percentile(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()]
        harnessLog(
            "scroll pass=$pass frames=${sorted.size} fps=${"%.1f".format(sorted.size / seconds)} " +
                "p50=${"%.1f".format(percentile(0.5))} p95=${"%.1f".format(percentile(0.95))} " +
                "p99=${"%.1f".format(percentile(0.99))} max=${"%.1f".format(sorted.lastOrNull() ?: 0.0)} " +
                "over20=${sorted.count { it > 20.0 }} over33=${sorted.count { it > 33.4 }} " +
                "cpu=${"%.1f".format(cpuPercent)} $lag",
        )
        intervals.clear()
        last = 0L
    }
}

fun main() {
    val passes = System.getProperty("kino.harness.passes")?.toIntOrNull() ?: 3
    val passSeconds = System.getProperty("kino.harness.pass-seconds")?.toDoubleOrNull() ?: 10.0
    harnessLog("start renderApi=${System.getProperty("skiko.renderApi")} passes=$passes")
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kino scroll harness",
            focusable = false,
            alwaysOnTop = true,
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            val posters = remember { List(PosterCount, ::poster) }
            val listState = rememberLazyListState()
            MaterialTheme(colorScheme = darkColorScheme()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0C0C0C)),
                    contentPadding = PaddingValues(bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item {
                        Box(Modifier.fillMaxWidth().height(460.dp)) {
                            Image(posters[0], null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0C0C0C))),
                                ),
                            )
                        }
                    }
                    items(RowCount) { row ->
                        Column {
                            Text(
                                "Catalog row $row",
                                color = Color.White,
                                fontSize = 20.sp,
                                modifier = Modifier.padding(start = 32.dp, bottom = 12.dp),
                            )
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 32.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                items(CardsPerRow) { column ->
                                    val image = posters[(row * CardsPerRow + column) % PosterCount]
                                    Box(
                                        Modifier.size(150.dp, 225.dp)
                                            .shadow(8.dp, RoundedCornerShape(12.dp))
                                            .clip(RoundedCornerShape(12.dp)),
                                    ) {
                                        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        Box(
                                            Modifier.fillMaxWidth().height(80.dp).align(Alignment.BottomCenter)
                                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))),
                                        )
                                        Text(
                                            "Item $column",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            DisposableEffect(window) {
                val pacing = installWindowsCompositorClockPacing(window)
                onDispose { pacing?.close() }
            }
            LaunchedEffect(Unit) {
                delay(3_000)
                repeat(passes) { pass ->
                    val recorder = FrameRecorder()
                    val startNs = System.nanoTime()
                    coroutineScope {
                        val frames = launch {
                            while (isActive) withFrameNanos(recorder::record)
                        }
                        var direction = 1f
                        while ((System.nanoTime() - startNs) / 1e9 < passSeconds) {
                            listState.animateScrollBy(direction * 1800f, tween(900, easing = LinearEasing))
                            if (!listState.canScrollForward || !listState.canScrollBackward) direction = -direction
                        }
                        frames.cancel()
                    }
                    recorder.drain(pass + 1, (System.nanoTime() - startNs) / 1e9)
                    delay(1_000)
                }
                harnessLog("action exit")
                exitApplication()
            }
        }
    }
}
