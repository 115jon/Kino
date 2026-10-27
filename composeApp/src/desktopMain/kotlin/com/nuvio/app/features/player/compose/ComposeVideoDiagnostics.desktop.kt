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
    private var presents = 0L
    private var unshown = 0L
    private var lastPresentNs = 0L
    private var presentGapNsMax = 0L
    private var presentGapsOver25Ms = 0L
    private var presentGapsOver58Ms = 0L
    private var pendingDepthTotal = 0L
    private var pendingDepthSamples = 0L

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
    fun recordUnshownFrame() {
        unshown += 1
    }

    @Synchronized
    fun recordPresent(nowNs: Long) {
        presents += 1
        val gap = nowNs - lastPresentNs
        if (lastPresentNs != 0L && gap < 1_000_000_000L) {
            presentGapNsMax = maxOf(presentGapNsMax, gap)
            if (gap > 25_000_000L) presentGapsOver25Ms += 1
            if (gap > 58_000_000L) presentGapsOver58Ms += 1
        }
        lastPresentNs = nowNs
    }

    @Synchronized
    fun recordPendingDepth(depth: Int) {
        pendingDepthTotal += depth
        pendingDepthSamples += 1
    }

    @Synchronized
    fun recordDraw() {
        draws += 1
    }

    @Synchronized
    fun drain(): String {
        val averageRenderMs = if (renders > 0) renderNsTotal / renders / 1_000_000.0 else 0.0
        val averageQueueMs = if (renders > 0) queueNsTotal / renders / 1_000_000.0 else 0.0
        val summary = "updates=$updateCallbacks renders=$renders presents=$presents unshown=$unshown draws=$draws " +
            "maxPresentGapMs=${"%.1f".format(presentGapNsMax / 1_000_000.0)} gapsOver25=$presentGapsOver25Ms gapsOver58=$presentGapsOver58Ms " +
            "avgPending=${"%.2f".format(if (pendingDepthSamples > 0) pendingDepthTotal.toDouble() / pendingDepthSamples else 0.0)} " +
            "avgRenderMs=${"%.2f".format(averageRenderMs)} maxRenderMs=${"%.2f".format(renderNsMax / 1_000_000.0)} " +
            "avgQueueMs=${"%.2f".format(averageQueueMs)} maxQueueMs=${"%.2f".format(queueNsMax / 1_000_000.0)}"
        updateCallbacks = 0
        renders = 0
        renderNsTotal = 0
        renderNsMax = 0
        queueNsTotal = 0
        queueNsMax = 0
        draws = 0
        presents = 0
        unshown = 0
        presentGapNsMax = 0
        presentGapsOver25Ms = 0
        presentGapsOver58Ms = 0
        pendingDepthTotal = 0
        pendingDepthSamples = 0
        return summary
    }
}
