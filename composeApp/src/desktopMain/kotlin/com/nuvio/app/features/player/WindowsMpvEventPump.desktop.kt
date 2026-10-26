package com.nuvio.app.features.player

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

internal class MpvEventPump(
    private val drain: () -> Unit,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Kino-mpv-events").apply { isDaemon = true }
    }
    private val requested = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    fun request() {
        if (closed.get()) return
        requested.set(true)
        if (scheduled.compareAndSet(false, true)) {
            submit()
        }
    }

    fun closeAndAwait() {
        if (!closed.compareAndSet(false, true)) return
        requested.set(false)
        executor.shutdown()
        try {
            executor.awaitTermination(5L, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        executor.shutdownNow()
    }

    private fun submit() {
        try {
            executor.execute(::drainLoop)
        } catch (_: RejectedExecutionException) {
            scheduled.set(false)
        }
    }

    private fun drainLoop() {
        try {
            while (!closed.get() && requested.getAndSet(false)) {
                drain()
            }
        } finally {
            scheduled.set(false)
            if (!closed.get() && requested.get() && scheduled.compareAndSet(false, true)) {
                submit()
            }
        }
    }
}
