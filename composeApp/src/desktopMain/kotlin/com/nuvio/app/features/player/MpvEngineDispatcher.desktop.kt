package com.nuvio.app.features.player

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal interface MpvEngineDispatcher {
    fun post(action: () -> Unit)

    fun postDelayed(delayMs: Long, action: () -> Unit)

    fun shutdown() {}
}

internal class MpvCommandThread : MpvEngineDispatcher {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "Kino-mpv-commands").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    override fun post(action: () -> Unit) {
        try {
            executor.execute { runAction(action) }
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        try {
            executor.schedule({ runAction(action) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
        }
    }

    override fun shutdown() {
        executor.shutdown()
    }

    fun awaitTermination(timeoutMs: Long): Boolean =
        executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)

    private fun runAction(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            desktopPlayerTrace(
                "mpv command failed type=${playbackMetadataForLog(error::class.simpleName)} " +
                    "message=${playbackMetadataForLog(error.message)}",
            )
        }
    }
}
