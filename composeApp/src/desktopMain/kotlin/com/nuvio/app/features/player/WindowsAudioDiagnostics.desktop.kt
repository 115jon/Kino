package com.nuvio.app.features.player

private val windowsAudioDiagnosticUrlPattern = Regex("https?://[^\\s|]+", RegexOption.IGNORE_CASE)
private const val MaxWindowsAudioDiagnosticInputLength = 4_096
internal const val MaxWindowsAudioDiagnosticValueLength = 96
internal const val MaxWindowsAudioDiagnosticSignatureLength = 1_024
internal const val MaxWindowsAudioDiagnosticLogLength = 4_096
internal const val MaxWindowsMpvTrackCount = 64

internal data class WindowsAudioDiagnostics(
    val selectedCodec: String? = null,
    val sourceCodec: String? = null,
    val sourceSampleRate: Int? = null,
    val sourceChannelCount: Int? = null,
    val sourceChannelLayout: String? = null,
    val sourceBitrate: Long? = null,
    val outputSampleRate: Int? = null,
    val outputChannelCount: Int? = null,
    val outputChannelLayout: String? = null,
    val outputFormat: String? = null,
    val configuredOutputDevice: String? = null,
    val activeOutputDevice: String? = null,
    val outputDriver: String? = null,
    val resampling: String? = null,
    val downmix: String? = null,
    val audioSpeedCorrection: Double? = null,
    val audioDelay: Double? = null,
    val sessionUnderrunCount: Long = 0L,
    val selectedTrackId: String? = null,
) {
    fun signature(): String = listOf(
        selectedCodec,
        sourceCodec,
        sourceSampleRate,
        sourceChannelCount,
        sourceChannelLayout,
        sourceBitrate,
        outputSampleRate,
        outputChannelCount,
        outputChannelLayout,
        outputFormat,
        configuredOutputDevice,
        activeOutputDevice,
        outputDriver,
        resampling,
        downmix,
        audioSpeedCorrection,
        audioDelay,
        sessionUnderrunCount,
        selectedTrackId,
    ).joinToString("|") { value ->
        boundWindowsAudioDiagnosticValue(value?.toString())
    }.take(MaxWindowsAudioDiagnosticSignatureLength)
}

internal class WindowsAudioDiagnosticsRateLimiter(
    private val minIntervalMs: Long = 2_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private var lastEmitMs: Long? = null
    private var lastSignature: String? = null

    @Synchronized
    fun shouldEmit(sourceGeneration: Long, diagnostics: WindowsAudioDiagnostics): Boolean {
        val signature = "$sourceGeneration|${diagnostics.signature()}"
        if (signature == lastSignature) return false
        val now = nowMs()
        val previous = lastEmitMs
        if (previous != null && now - previous < minIntervalMs) return false
        lastEmitMs = now
        lastSignature = signature
        return true
    }
}

internal class WindowsAudioUnderrunTracker {
    private var sessionUnderrunCount = 0L

    @Synchronized
    fun record(isUnderrun: Boolean): Boolean {
        if (!isUnderrun) return false
        sessionUnderrunCount += 1L
        return true
    }

    @Synchronized
    fun count(): Long = sessionUnderrunCount
}

internal fun parseWindowsAudioInt(value: String?): Int? =
    value?.trim()?.toIntOrNull()?.takeIf { it > 0 }

internal fun parseWindowsAudioLong(value: String?): Long? =
    value?.trim()?.toLongOrNull()?.takeIf { it > 0L }

internal fun parseWindowsAudioDouble(value: String?): Double? =
    value?.trim()?.toDoubleOrNull()?.takeIf(Double::isFinite)

internal fun boundedWindowsMpvTrackCount(value: String?): Int =
    value?.trim()?.toIntOrNull()?.coerceIn(0, MaxWindowsMpvTrackCount) ?: 0

internal fun selectWindowsAudioOutputDriver(
    currentAo: String?,
    configuredDriver: String?,
    legacyDriver: String?,
): String? = listOf(currentAo, configuredDriver, legacyDriver)
    .firstOrNull { !it.isNullOrBlank() }
    ?.trim()

internal fun windowsAudioResamplingState(sourceSampleRate: Int?, outputSampleRate: Int?): String? =
    when {
        sourceSampleRate == null || outputSampleRate == null -> null
        sourceSampleRate == outputSampleRate -> "not-detected"
        else -> "active"
    }

internal fun windowsAudioDownmixState(sourceChannelCount: Int?, outputChannelCount: Int?): String? =
    when {
        sourceChannelCount == null || outputChannelCount == null -> null
        outputChannelCount < sourceChannelCount -> "active"
        else -> "not-detected"
    }

internal fun isWindowsAudioUnderrunLog(value: String): Boolean {
    val normalized = value.lowercase()
    val hasAudioContext = normalized.contains("audio") || normalized.contains("ao:") || normalized.contains("ao ")
    val hasUnderrun = normalized.contains("underrun") ||
        normalized.contains("under-run") ||
        normalized.contains("under run")
    return hasAudioContext && hasUnderrun
}

internal fun formatWindowsAudioDiagnostics(
    trigger: String,
    diagnostics: WindowsAudioDiagnostics,
): String = buildString {
    append("audio diagnostics trigger=")
    append(boundWindowsAudioDiagnosticValue(trigger))
    append(" selectedCodec=")
    append(boundWindowsAudioDiagnosticValue(diagnostics.selectedCodec))
    append(" sourceCodec=")
    append(boundWindowsAudioDiagnosticValue(diagnostics.sourceCodec))
    append(" sourceSampleRate=")
    append(diagnostics.sourceSampleRate ?: "unknown")
    append(" sourceChannelCount=")
    append(diagnostics.sourceChannelCount ?: "unknown")
    append(" sourceChannelLayout=")
    append(boundWindowsAudioDiagnosticValue(diagnostics.sourceChannelLayout))
    append(" sourceBitrate=")
    append(diagnostics.sourceBitrate ?: "unknown")
    append(" outputSampleRate=")
    append(diagnostics.outputSampleRate ?: "unknown")
    append(" outputChannelCount=")
    append(diagnostics.outputChannelCount ?: "unknown")
    append(" outputChannelLayout=")
    append(boundWindowsAudioDiagnosticValue(diagnostics.outputChannelLayout))
    append(" outputFormat=")
    append(boundWindowsAudioDiagnosticValue(diagnostics.outputFormat))
    append(" configuredOutputDevice=")
    append(boundWindowsAudioDiagnosticValue(diagnostics.configuredOutputDevice))
    append(" activeOutputDevice=")
    append(boundWindowsAudioDiagnosticValue(diagnostics.activeOutputDevice))
    append(" outputDriver=")
    append(boundWindowsAudioDiagnosticValue(diagnostics.outputDriver))
    append(" resampling=")
    append(boundWindowsAudioDiagnosticValue(diagnostics.resampling))
    append(" downmix=")
    append(boundWindowsAudioDiagnosticValue(diagnostics.downmix))
    append(" audioSpeedCorrection=")
    append(diagnostics.audioSpeedCorrection ?: "unknown")
    append(" audioDelay=")
    append(diagnostics.audioDelay ?: "unknown")
    append(" sessionUnderrunCount=")
    append(diagnostics.sessionUnderrunCount)
}.take(MaxWindowsAudioDiagnosticLogLength)

internal fun boundWindowsAudioDiagnosticValue(value: String?): String =
    value
        ?.take(MaxWindowsAudioDiagnosticInputLength)
        ?.let(::stripWindowsMpvLogControls)
        ?.let { windowsAudioDiagnosticUrlPattern.replace(it, "<redacted-url>") }
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.take(MaxWindowsAudioDiagnosticValueLength)
        ?: "unknown"
