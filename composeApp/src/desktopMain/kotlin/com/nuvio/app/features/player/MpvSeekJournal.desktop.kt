package com.nuvio.app.features.player

internal const val MpvPositionRegressionThresholdMs = 1_500L
internal const val MpvSeekAttributionWindowMs = 4_000L

private val seekPlumbingClasses = setOf(
    "MpvEngine",
    "MpvSeekJournal_desktopKt",
    "WindowsPlayerEngineController",
    "DesktopMediaKeySession",
)

internal data class MpvSeekRecord(
    val kind: String,
    val cause: String,
    val targetMs: Long?,
    val fromMs: Long?,
    val atMs: Long,
)

internal class MpvSeekJournal(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile
    var lastSeek: MpvSeekRecord? = null
        private set

    private var lastPositionMs = -1L
    private var lastPositionAtMs = 0L
    private var lastGeneration = Long.MIN_VALUE

    fun record(kind: String, cause: String, targetMs: Long?, fromMs: Long?): MpvSeekRecord =
        MpvSeekRecord(kind, cause, targetMs, fromMs, clock()).also { lastSeek = it }

    fun observePosition(generation: Long, positionMs: Long, seeking: Boolean): String? {
        val now = clock()
        if (generation != lastGeneration) {
            lastGeneration = generation
            lastPositionMs = -1L
        }
        val previous = lastPositionMs
        val previousAt = lastPositionAtMs
        if (seeking) return null
        lastPositionMs = positionMs
        lastPositionAtMs = now
        if (previous < 0L || positionMs >= previous - MpvPositionRegressionThresholdMs) return null
        val seek = lastSeek?.takeIf { now - it.atMs <= MpvSeekAttributionWindowMs }
        if (seek != null) return null
        return "position regressed fromMs=$previous toMs=$positionMs deltaMs=${positionMs - previous} " +
            "sincePreviousMs=${now - previousAt} ${describeLastSeek(now)}"
    }

    fun describeLastSeek(now: Long = clock()): String {
        val seek = lastSeek ?: return "lastSeek=none"
        return "lastSeek=${seek.kind} lastSeekCause=${seek.cause} lastSeekAgeMs=${now - seek.atMs}"
    }
}

internal fun mpvSeekCallSite(stack: Array<StackTraceElement> = Throwable().stackTrace): String =
    stack.firstOrNull { frame ->
        val outerName = frame.className.substringAfterLast('.').substringBefore('$')
        frame.className.startsWith("com.nuvio.") && outerName !in seekPlumbingClasses
    }?.let { frame -> "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}" } ?: "unknown"
