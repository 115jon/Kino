package com.nuvio.app.features.player.compose

internal class VideoFrameQueue<T : Any>(private val capacity: Int) {
    private val pending = ArrayDeque<T>(capacity + 1)

    val size: Int get() = pending.size

    operator fun contains(frame: T): Boolean = frame in pending

    fun push(frame: T, redraw: Boolean, presenting: Boolean): Int {
        if (redraw) pending.removeLastOrNull()
        pending.addLast(frame)
        val limit = if (presenting) capacity else 1
        var dropped = 0
        while (pending.size > limit) {
            pending.removeFirst()
            dropped += 1
        }
        return dropped
    }

    fun take(): T? = pending.removeFirstOrNull()

    fun keepNewest() {
        while (pending.size > 1) pending.removeFirst()
    }

    fun clear() {
        pending.clear()
    }
}
