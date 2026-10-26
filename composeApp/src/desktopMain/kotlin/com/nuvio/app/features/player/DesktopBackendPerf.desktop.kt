package com.nuvio.app.features.player

import co.touchlab.kermit.Logger

private val desktopPlayerPerfLog = Logger.withTag("DesktopPlayerPerf")

private class DesktopBackendPerfStats {
    private var lastLogNs = System.nanoTime()
    private var wakeupBursts = 0L
    private var wakeupEvents = 0L
    private var updateCallbacks = 0L
    private var fallbackSignals = 0L
    private var sourceLoadSignals = 0L
    private var polls = 0L
    private var pollDurationNsTotal = 0L
    private var renderFrames = 0L
    private var renderDurationNsTotal = 0L
    private var renderQueueDelayNsTotal = 0L
    private var renderDurationNsMax = 0L
    private var renderQueueDelayNsMax = 0L

    @Synchronized
    fun recordWakeup(events: Int) {
        wakeupBursts += 1
        wakeupEvents += events.toLong()
        maybeLog()
    }

    @Synchronized
    fun recordRenderUpdateCallback() {
        updateCallbacks += 1
        maybeLog()
    }

    @Synchronized
    fun recordFallbackSignal() {
        fallbackSignals += 1
        maybeLog()
    }

    @Synchronized
    fun recordSourceLoadSignal() {
        sourceLoadSignals += 1
        maybeLog()
    }

    @Synchronized
    fun recordPoll(durationNs: Long, playing: Boolean, loading: Boolean, idle: Boolean) {
        polls += 1
        pollDurationNsTotal += durationNs
        maybeLog(playing, loading, idle)
    }

    @Synchronized
    fun recordRenderFrame(durationNs: Long, queueDelayNs: Long) {
        renderFrames += 1L
        renderDurationNsTotal += durationNs
        renderQueueDelayNsTotal += queueDelayNs
        renderDurationNsMax = maxOf(renderDurationNsMax, durationNs)
        renderQueueDelayNsMax = maxOf(renderQueueDelayNsMax, queueDelayNs)
        maybeLog()
    }

    @Synchronized
    private fun maybeLog(
        playing: Boolean = false,
        loading: Boolean = false,
        idle: Boolean = false,
    ) {
        val nowNs = System.nanoTime()
        if (nowNs - lastLogNs < 2_000_000_000L) {
            return
        }
        val averagePollMs = if (polls > 0L) {
            (pollDurationNsTotal.toDouble() / polls.toDouble()) / 1_000_000.0
        } else {
            0.0
        }
        val averageRenderMs = if (renderFrames > 0L) {
            (renderDurationNsTotal.toDouble() / renderFrames.toDouble()) / 1_000_000.0
        } else {
            0.0
        }
        val averageRenderQueueMs = if (renderFrames > 0L) {
            (renderQueueDelayNsTotal.toDouble() / renderFrames.toDouble()) / 1_000_000.0
        } else {
            0.0
        }
        desktopPlayerPerfLog.d {
            "backend wakeupBursts=$wakeupBursts wakeupEvents=$wakeupEvents updateCallbacks=$updateCallbacks fallbackSignals=$fallbackSignals sourceLoadSignals=$sourceLoadSignals polls=$polls avgPollMs=${"%.2f".format(averagePollMs)} renderFrames=$renderFrames avgRenderMs=${"%.2f".format(averageRenderMs)} maxRenderMs=${"%.2f".format(renderDurationNsMax / 1_000_000.0)} avgRenderQueueMs=${"%.2f".format(averageRenderQueueMs)} maxRenderQueueMs=${"%.2f".format(renderQueueDelayNsMax / 1_000_000.0)} playing=$playing loading=$loading idle=$idle"
        }
        lastLogNs = nowNs
        wakeupBursts = 0L
        wakeupEvents = 0L
        updateCallbacks = 0L
        fallbackSignals = 0L
        sourceLoadSignals = 0L
        polls = 0L
        pollDurationNsTotal = 0L
        renderFrames = 0L
        renderDurationNsTotal = 0L
        renderQueueDelayNsTotal = 0L
        renderDurationNsMax = 0L
        renderQueueDelayNsMax = 0L
    }
}

internal class DesktopBackendPerfCollector {
    private val stats = DesktopBackendPerfStats()

    fun recordWakeup(events: Int) = stats.recordWakeup(events)

    fun recordRenderUpdateCallback() = stats.recordRenderUpdateCallback()

    fun recordFallbackSignal() = stats.recordFallbackSignal()

    fun recordSourceLoadSignal() = stats.recordSourceLoadSignal()

    fun recordPoll(durationNs: Long, playing: Boolean, loading: Boolean, idle: Boolean) =
        stats.recordPoll(durationNs, playing, loading, idle)

    fun recordRenderFrame(durationNs: Long, queueDelayNs: Long) =
        stats.recordRenderFrame(durationNs, queueDelayNs)
}
