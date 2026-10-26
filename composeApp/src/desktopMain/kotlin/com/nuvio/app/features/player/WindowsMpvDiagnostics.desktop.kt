package com.nuvio.app.features.player

internal data class MpvVideoDiagnostics(
    val codec: String?,
    val decoder: String?,
    val hardwareDecoder: String?,
    val format: String?,
    val pixelFormat: String?,
    val hardwarePixelFormat: String?,
    val primaries: String?,
    val transfer: String?,
    val matrix: String?,
    val levels: String?,
    val signalPeak: String?,
    val dolbyVision: String?,
) {
    fun signature(): String = listOf(
        codec,
        decoder,
        hardwareDecoder,
        format,
        pixelFormat,
        hardwarePixelFormat,
        primaries,
        transfer,
        matrix,
        levels,
        signalPeak,
        dolbyVision,
    ).joinToString("|") { value -> playbackMetadataForLog(value) }
        .take(MaxWindowsMpvLogMessageLength)

    fun logValue(): String =
        (
            "codec=${playbackMetadataForLog(codec)} decoder=${playbackMetadataForLog(decoder)} " +
                "hwdec=${playbackMetadataForLog(hardwareDecoder)} format=${playbackMetadataForLog(format)} " +
                "pixelformat=${playbackMetadataForLog(pixelFormat)} " +
                "hwPixelformat=${playbackMetadataForLog(hardwarePixelFormat)} " +
                "primaries=${playbackMetadataForLog(primaries)} " +
                "transfer=${playbackMetadataForLog(transfer)} matrix=${playbackMetadataForLog(matrix)} " +
                "levels=${playbackMetadataForLog(levels)} sigPeak=${playbackMetadataForLog(signalPeak)} " +
                "dolbyVision=${playbackMetadataForLog(dolbyVision)}"
            ).let(::boundWindowsMpvLogMessage)

    fun toMetadata(state: MpvPlaybackState): DesktopVideoMetadata = DesktopVideoMetadata(
        codec = codec ?: format,
        pixelFormat = pixelFormat,
        hardwarePixelFormat = hardwarePixelFormat,
        primaries = primaries,
        transfer = transfer,
        matrix = matrix,
        signalPeak = signalPeak?.toDoubleOrNull(),
        dolbyVision = dolbyVision,
        externalHdrLike = state.streamIsHdrLike,
        externalDolbyVision = state.streamHasDolbyVision,
    )
}

internal fun readMpvVideoDiagnostics(
    client: MpvClient,
): MpvVideoDiagnostics = MpvVideoDiagnostics(
    codec = client.readNonBlankProperty("video-codec"),
    decoder = client.readNonBlankProperty("video-decoder"),
    hardwareDecoder = client.readNonBlankProperty("hwdec-current"),
    format = client.readNonBlankProperty("video-format"),
    pixelFormat = client.readNonBlankProperty("video-params/pixelformat"),
    hardwarePixelFormat = client.readNonBlankProperty("video-params/hw-pixelformat"),
    primaries = client.readNonBlankProperty("video-params/primaries"),
    transfer = client.readNonBlankProperty("video-params/gamma"),
    matrix = client.readNonBlankProperty("video-params/colormatrix"),
    levels = client.readNonBlankProperty("video-params/colorlevels"),
    signalPeak = client.readNonBlankProperty("video-params/sig-peak"),
    dolbyVision = client.readNonBlankProperty("video-params/dolby-vision"),
)

internal fun readMpvAudioDiagnostics(
    client: MpvClient,
    state: MpvPlaybackState,
): WindowsAudioDiagnostics {
    fun get(name: String): String? = client.readNonBlankProperty(name)
    val trackCount = boundedWindowsMpvTrackCount(get("track-list/count"))
    val selectedAudioTrackIndex = (0 until trackCount).firstOrNull { index ->
        get("track-list/$index/type") == "audio" && get("track-list/$index/selected") == "yes"
    }
    fun selectedTrackValue(name: String): String? = selectedAudioTrackIndex?.let { get("track-list/$it/$name") }

    val sourceSampleRate = parseWindowsAudioInt(get("audio-params/samplerate"))
        ?: parseWindowsAudioInt(selectedTrackValue("demux-samplerate"))
    val sourceChannelCount = parseWindowsAudioInt(get("audio-params/channel-count"))
        ?: parseWindowsAudioInt(selectedTrackValue("demux-channel-count"))
    val sourceChannelLayout = get("audio-params/channel-layout")
        ?: get("audio-params/channels")
        ?: selectedTrackValue("demux-channels")
    val sourceBitrate = parseWindowsAudioLong(get("audio-bitrate"))
        ?: parseWindowsAudioLong(get("audio-params/bitrate"))
        ?: parseWindowsAudioLong(selectedTrackValue("demux-bitrate"))
    val sourceCodec = selectedTrackValue("codec") ?: get("audio-params/codec")
    val selectedCodec = get("audio-codec") ?: sourceCodec
    val outputSampleRate = parseWindowsAudioInt(get("audio-out-params/samplerate"))
    val outputChannelCount = parseWindowsAudioInt(get("audio-out-params/channel-count"))
    val outputChannelLayout = get("audio-out-params/channel-layout")
        ?: get("audio-out-params/channels")

    return WindowsAudioDiagnostics(
        selectedCodec = selectedCodec,
        sourceCodec = sourceCodec,
        sourceSampleRate = sourceSampleRate,
        sourceChannelCount = sourceChannelCount,
        sourceChannelLayout = sourceChannelLayout,
        sourceBitrate = sourceBitrate,
        outputSampleRate = outputSampleRate,
        outputChannelCount = outputChannelCount,
        outputChannelLayout = outputChannelLayout,
        outputFormat = get("audio-out-params/format"),
        configuredOutputDevice = get("audio-device"),
        activeOutputDevice = get("audio-out-params/device") ?: get("audio-device-name"),
        outputDriver = selectWindowsAudioOutputDriver(
            currentAo = get("current-ao"),
            configuredDriver = get("audio-driver"),
            legacyDriver = get("ao"),
        ),
        resampling = windowsAudioResamplingState(sourceSampleRate, outputSampleRate),
        downmix = windowsAudioDownmixState(sourceChannelCount, outputChannelCount),
        audioSpeedCorrection = parseWindowsAudioDouble(get("audio-speed-correction")),
        audioDelay = parseWindowsAudioDouble(get("audio-delay")),
        sessionUnderrunCount = state.audioUnderrunTracker.count(),
        selectedTrackId = selectedAudioTrackIndex?.let { get("track-list/$it/id") },
    )
}

internal fun recordWindowsAudioUnderrun(state: MpvPlaybackState, text: String) {
    state.audioUnderrunTracker.record(isWindowsAudioUnderrunLog(text))
}

internal fun markWindowsAudioDiagnosticsRequested(state: MpvPlaybackState, name: String) {
    if (name == "aid" || name == "track-list/count" || name.startsWith("audio-")) {
        state.audioDiagnosticsRequested = true
    }
}
