package com.nuvio.app.features.player

internal data class DesktopVideoMetadata(
    val codec: String? = null,
    val pixelFormat: String? = null,
    val hardwarePixelFormat: String? = null,
    val primaries: String? = null,
    val transfer: String? = null,
    val matrix: String? = null,
    val signalPeak: Double? = null,
    val dolbyVision: String? = null,
    val externalHdrLike: Boolean = false,
    val externalDolbyVision: Boolean = false,
)

internal enum class DesktopVideoPipelineMode {
    Standard,
    HdrCompatibility,
    DolbyVisionCompatibility,
}

internal data class DesktopVideoPipelineDecision(
    val mode: DesktopVideoPipelineMode,
    val reason: String,
)

internal fun selectDesktopVideoPipeline(metadata: DesktopVideoMetadata): DesktopVideoPipelineDecision {
    if (
        metadata.externalDolbyVision ||
        metadata.codec.looksLikeDolbyVision() ||
        metadata.dolbyVision.looksLikeDolbyVision() ||
        metadata.matrix.looksLikeDolbyVision()
    ) {
        return DesktopVideoPipelineDecision(
            mode = DesktopVideoPipelineMode.DolbyVisionCompatibility,
            reason = "dolby-vision metadata",
        )
    }

    val transfer = metadata.transfer.normalizedValue()
    val primaries = metadata.primaries.normalizedValue()
    val pixelFormat = metadata.pixelFormat.normalizedValue()
    val hardwarePixelFormat = metadata.hardwarePixelFormat.normalizedValue()
    val hdrTransfer = transfer in setOf("pq", "st2084", "smpte2084", "hlg", "arib-std-b67")
    val wideGamut = primaries == "bt.2020" || primaries == "bt2020"
    val highBitDepth = pixelFormat.contains("10") || pixelFormat.contains("12") ||
        hardwarePixelFormat.contains("10") || hardwarePixelFormat.contains("12")
    val hdrSignal = metadata.signalPeak != null && metadata.signalPeak > 1.0

    if (metadata.externalHdrLike || hdrTransfer || (wideGamut && (highBitDepth || hdrSignal))) {
        return DesktopVideoPipelineDecision(
            mode = DesktopVideoPipelineMode.HdrCompatibility,
            reason = "hdr color metadata",
        )
    }

    return DesktopVideoPipelineDecision(
        mode = DesktopVideoPipelineMode.Standard,
        reason = "sdr color metadata",
    )
}

private fun String?.normalizedValue(): String = orEmpty().trim().lowercase()

private fun String?.looksLikeDolbyVision(): Boolean {
    val value = normalizedValue()
    return value.contains("dolby") || value == "dv" || value.contains("dvhe") || value.contains("dvh1")
}
