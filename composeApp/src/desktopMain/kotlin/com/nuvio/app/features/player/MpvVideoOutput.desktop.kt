package com.nuvio.app.features.player

internal data class MpvOption(
    val name: String,
    val value: String,
    val required: Boolean = false,
)

internal interface MpvVideoOutput {
    val logLevel: String

    fun options(): List<MpvOption>

    fun attach(client: MpvClient) {}

    fun detach(client: MpvClient) {}
}

internal class WidOutput(private val hwnd: Long) : MpvVideoOutput {
    override val logLevel: String = "info"

    override fun options(): List<MpvOption> = listOf(
        MpvOption("wid", hwnd.toString(), required = true),
        MpvOption("vo", "gpu-next,gpu"),
        MpvOption("gpu-api", "d3d11", required = true),
        MpvOption("gpu-context", "d3d11", required = true),
        MpvOption("d3d11-output-mode", "window", required = true),
        MpvOption("d3d11-exclusive-fs", "no", required = true),
        MpvOption("d3d11-flip", "no", required = true),
    )
}

internal val mpvEngineCommonOptions: List<MpvOption> = listOf(
    MpvOption("input-media-keys", "no"),
    MpvOption("media-controls", "no"),
    MpvOption("subs-match-os-language", "yes"),
    MpvOption("subs-fallback", "yes"),
    MpvOption("sub-ass-override", "force"),
    MpvOption("hwdec", "auto-copy-safe"),
    MpvOption("target-colorspace-hint", "yes"),
    MpvOption("hdr-compute-peak", "yes"),
    MpvOption("tone-mapping", "auto"),
    MpvOption("keep-open", "yes"),
    MpvOption("cache", "yes"),
    MpvOption("cache-pause", "yes"),
    MpvOption("cache-pause-wait", WindowsPlayerCachePauseWaitSeconds),
    MpvOption("demuxer-max-bytes", WindowsPlayerMaxCacheBytes),
    MpvOption("demuxer-max-back-bytes", WindowsPlayerMaxBackCacheBytes),
    MpvOption("demuxer-readahead-secs", WindowsPlayerReadAheadSeconds),
)

internal fun mpvEngineOptions(output: MpvVideoOutput): List<MpvOption> {
    val outputOptions = output.options()
    val outputNames = outputOptions.mapTo(HashSet()) { it.name }
    return outputOptions + mpvEngineCommonOptions.filterNot { it.name in outputNames }
}
