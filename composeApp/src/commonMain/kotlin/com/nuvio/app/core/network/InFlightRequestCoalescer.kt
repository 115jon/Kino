package com.nuvio.app.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class InFlightRequestCoalescer<K : Any, V>(
    private val reuseWindowMs: Long,
    private val currentTimeMillis: () -> Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private class Completed<V>(val value: V, val completedAtMs: Long)

    private val lock = Mutex()
    private val inFlight = mutableMapOf<K, Deferred<V>>()
    private val completed = mutableMapOf<K, Completed<V>>()

    suspend fun run(key: K, allowReuse: Boolean = true, block: suspend () -> V): V {
        val request = lock.withLock {
            val now = currentTimeMillis()
            completed.entries.removeAll { now - it.value.completedAtMs >= reuseWindowMs }
            if (allowReuse) {
                completed[key]?.let { return it.value }
            }
            inFlight[key]?.takeIf { it.isActive } ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    block().also { value ->
                        lock.withLock { completed[key] = Completed(value, currentTimeMillis()) }
                    }
                } finally {
                    lock.withLock { inFlight.remove(key) }
                }
            }.also { inFlight[key] = it }
        }
        request.start()
        return request.await()
    }
}
