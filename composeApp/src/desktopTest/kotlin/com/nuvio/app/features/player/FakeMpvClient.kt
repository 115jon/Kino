package com.nuvio.app.features.player

import com.sun.jna.Pointer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

internal class FakeMpvClient : MpvClient {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val properties = ConcurrentHashMap<String, String>()
    val events = ConcurrentLinkedDeque<MpvClientEvent>()
    val optionResults = mutableMapOf<String, Int>()
    val propertyResults = mutableMapOf<String, Int>()
    var initializeResult = 0
    var commandResult: (List<String>) -> Int = { 0 }

    @Volatile
    var wakeup: (() -> Unit)? = null

    override val nativeHandle: Pointer? = null

    override fun setOption(name: String, value: String): Int {
        calls += "option $name=$value"
        return optionResults[name] ?: 0
    }

    override fun initialize(): Int {
        calls += "initialize"
        return initializeResult
    }

    override fun requestLogMessages(minLevel: String): Int {
        calls += "log $minLevel"
        return 0
    }

    override fun observeProperty(name: String, format: Int): Int {
        calls += "observe $name"
        return 0
    }

    override fun setProperty(name: String, value: String): Int {
        calls += "set $name=$value"
        return propertyResults[name] ?: 0
    }

    override fun getProperty(name: String): String? = properties[name]

    override fun command(vararg args: String): Int {
        calls += "command ${args.joinToString(" ")}"
        return commandResult(args.toList())
    }

    override fun errorString(error: Int): String? = null

    override fun waitEvent(timeoutSeconds: Double): MpvClientEvent? = events.pollFirst()

    override fun setWakeupListener(listener: (() -> Unit)?) {
        calls += "wakeup ${listener != null}"
        wakeup = listener
    }

    override fun terminateDestroy() {
        calls += "terminate"
    }

    fun clearCalls() {
        calls.clear()
    }

    fun snapshotCalls(): List<String> = synchronized(calls) { calls.toList() }
}

internal class ManualMpvDispatcher(
    private val runPostsImmediately: Boolean = true,
) : MpvEngineDispatcher {
    val posted = mutableListOf<() -> Unit>()
    val delayed = mutableListOf<Pair<Long, () -> Unit>>()

    override fun post(action: () -> Unit) {
        if (runPostsImmediately) action() else posted += action
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        delayed += delayMs to action
    }

    fun runPosted() {
        val actions = posted.toList()
        posted.clear()
        actions.forEach { it() }
    }

    fun runDelayed(delayMs: Long) {
        val actions = delayed.filter { it.first == delayMs }
        delayed.removeAll(actions)
        actions.forEach { it.second() }
    }
}

internal class CountingMpvDispatcher : MpvEngineDispatcher {
    var posts = 0
    var shutdowns = 0

    override fun post(action: () -> Unit) {
        posts += 1
        action()
    }

    override fun postDelayed(delayMs: Long, action: () -> Unit) {
        posts += 1
    }

    override fun shutdown() {
        shutdowns += 1
    }
}
