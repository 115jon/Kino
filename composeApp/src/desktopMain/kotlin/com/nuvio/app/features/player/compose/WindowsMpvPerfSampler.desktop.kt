package com.nuvio.app.features.player.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.player.WindowsPlayerWindowState
import java.lang.management.ManagementFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val PerfSampleIntervalMs =
    System.getProperty("kino.player.perf-interval-ms")?.toLongOrNull()?.coerceAtLeast(250L) ?: 5_000L

private val perfProperties = listOf(
    "current-vo",
    "hwdec-current",
    "video-params/w",
    "video-params/h",
    "video-params/gamma",
    "video-out-params/pixelformat",
    "video-out-params/colormatrix",
    "video-out-params/gamma",
    "video-out-params/chroma-location",
    "vf",
    "container-fps",
    "estimated-vf-fps",
    "display-fps",
    "estimated-display-fps",
    "frame-drop-count",
    "decoder-frame-drop-count",
    "vo-delayed-frame-count",
    "mistimed-frame-count",
    "vsync-jitter",
    "avsync",
    "time-pos",
    "pause",
    "speed",
    "aid",
    "sid",
    "current-tracks/sub/codec",
    "current-tracks/sub/external",
)

@Composable
internal fun WindowsMpvPerfSampler(state: WindowsPlayerWindowState, surface: String) {
    if (System.getProperty("kino.player.perf-log") != "true") return
    LaunchedEffect(state) {
        val osBean = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
        while (!state.isClosed) {
            delay(PerfSampleIntervalMs)
            val line = withContext(Dispatchers.IO) {
                var values: String? = null
                state.engine.withClient { client ->
                    values = perfProperties.joinToString(" ") { name -> "$name=${client.getProperty(name).orEmpty()}" }
                }
                val sampled = values ?: return@withContext null
                val cpu = osBean?.processCpuLoad?.let { "%.1f".format(it * 100.0) }.orEmpty()
                val renderStats = (state.panelRef as? WindowsComposePlayerPanel)?.stats?.drain()?.let { " $it" }.orEmpty()
                "player perf surface=$surface processCpu=$cpu $sampled$renderStats"
            } ?: continue
            withContext(Dispatchers.IO) { composeVideoTrace(line) }
        }
    }
}
