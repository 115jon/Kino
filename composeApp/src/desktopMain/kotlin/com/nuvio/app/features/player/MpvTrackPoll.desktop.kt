package com.nuvio.app.features.player

internal data class MpvTrackPoll(
    val audioTracks: List<AudioTrack>,
    val subtitleTracks: List<SubtitleTrack>,
    val metadataLog: String?,
)

internal fun pollMpvTracks(client: MpvClient, state: MpvPlaybackState): MpvTrackPoll {
    fun getString(name: String): String? = client.getProperty(name)

    val trackMetadataDiagnostics = mutableListOf<String>()
    var trackMetadataLog: String? = null
    val trackCount = boundedWindowsMpvTrackCount(getString("track-list/count"))
    val nextAudioTracks = mutableListOf<AudioTrack>()
    val nextSubtitleTracks = mutableListOf<SubtitleTrack>()
    var audioIdx = 0
    var subIdx = 0

    for (i in 0 until trackCount) {
        val type = getString("track-list/$i/type") ?: ""
        val metadata = windowsMpvTrackMetadataForModel(
            id = getString("track-list/$i/id"),
            title = getString("track-list/$i/title"),
            language = getString("track-list/$i/lang"),
            codec = getString("track-list/$i/codec"),
        )
        val id = metadata.id
        val title = metadata.title
        val lang = metadata.language
        val codec = metadata.codec
        val external = getString("track-list/$i/external") == "yes"
        val selected = getString("track-list/$i/selected") == "yes"
        trackMetadataDiagnostics +=
            "${playbackMetadataForLog(type)}[$i] id=${playbackMetadataForLog(id)} " +
                "title=${playbackMetadataForLog(title).ifBlank { "<empty>" }} " +
                "lang=${playbackMetadataForLog(lang).ifBlank { "<empty>" }} " +
                "codec=${playbackMetadataForLog(codec).ifBlank { "<empty>" }} external=$external"

        if (type == "audio") {
            val sampleRate = parseWindowsAudioInt(getString("track-list/$i/demux-samplerate"))
            val channelCount = parseWindowsAudioInt(getString("track-list/$i/demux-channel-count"))
            val channelLayout = getString("track-list/$i/demux-channels")
                ?.take(MaxWindowsMpvChannelLayoutLength)
            val bitrate = parseWindowsAudioLong(getString("track-list/$i/demux-bitrate"))
            nextAudioTracks.add(
                AudioTrack(
                    index = audioIdx++,
                    id = id,
                    label = title,
                    language = lang,
                    codec = codec.takeIf { it.isNotBlank() },
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    channelLayout = channelLayout,
                    bitrate = bitrate,
                    isSelected = selected
                )
            )
        } else if (type == "sub") {
            nextSubtitleTracks.add(
                SubtitleTrack(
                    index = subIdx++,
                    id = id,
                    label = title,
                    language = lang,
                    isSelected = selected,
                    isForced = inferForcedSubtitleTrack(title, lang, id)
                )
            )
        }
    }
    val trackMetadataSignature = trackMetadataDiagnostics
        .joinToString("||")
        .take(MaxWindowsMpvLogMessageLength)
    if (trackMetadataSignature != state.lastTrackMetadataSignature) {
        state.lastTrackMetadataSignature = trackMetadataSignature
        trackMetadataLog = trackMetadataDiagnostics.takeIf { it.isNotEmpty() }?.joinToString(" || ") {
            "mpv track metadata $it"
        }?.take(MaxWindowsMpvLogMessageLength)
    }
    return MpvTrackPoll(nextAudioTracks, nextSubtitleTracks, trackMetadataLog)
}
