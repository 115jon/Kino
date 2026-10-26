package com.nuvio.app.features.player.compose

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.boundWindowsMpvLogMessage
import com.nuvio.app.features.player.redactMpvLogText

private val composeVideoLog = Logger.withTag("DesktopPlayerTrace")

internal fun composeVideoTrace(message: String) {
    composeVideoLog.i { boundWindowsMpvLogMessage(redactMpvLogText(message)) }
}

internal class ComposeVideoStats {
    private var updateCallbacks = 0L
    private var renders = 0L
    private var renderNsTotal = 0L
    private var renderNsMax = 0L
    private var queueNsTotal = 0L
    private var queueNsMax = 0L
    private var draws = 0L

    @Synchronized
    fun recordUpdateCallback() {
        updateCallbacks += 1
    }

    @Synchronized
    fun recordRender(durationNs: Long, queueNs: Long) {
        renders += 1
        renderNsTotal += durationNs
        renderNsMax = maxOf(renderNsMax, durationNs)
        queueNsTotal += queueNs
        queueNsMax = maxOf(queueNsMax, queueNs)
    }

    @Synchronized
    fun recordDraw() {
        draws += 1
    }

    @Synchronized
    fun drain(): String {
        val averageRenderMs = if (renders > 0) renderNsTotal / renders / 1_000_000.0 else 0.0
        val averageQueueMs = if (renders > 0) queueNsTotal / renders / 1_000_000.0 else 0.0
        val summary = "updates=$updateCallbacks renders=$renders draws=$draws " +
            "avgRenderMs=${"%.2f".format(averageRenderMs)} maxRenderMs=${"%.2f".format(renderNsMax / 1_000_000.0)} " +
            "avgQueueMs=${"%.2f".format(averageQueueMs)} maxQueueMs=${"%.2f".format(queueNsMax / 1_000_000.0)}"
        updateCallbacks = 0
        renders = 0
        renderNsTotal = 0
        renderNsMax = 0
        queueNsTotal = 0
        queueNsMax = 0
        draws = 0
        return summary
    }
}
