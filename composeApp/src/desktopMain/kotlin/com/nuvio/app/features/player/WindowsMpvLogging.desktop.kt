package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import java.net.URI

private val desktopPlayerLog = Logger.withTag("DesktopPlayerTrace")
internal const val MaxWindowsMpvMetadataFieldLength = 96
internal const val MaxWindowsMpvLogMessageLength = 4_096
private const val MaxWindowsMpvTrackIdLength = 16
private const val MaxWindowsMpvTrackTitleLength = 256
private const val MaxWindowsMpvTrackLanguageLength = 64
private const val MaxWindowsMpvTrackCodecLength = 128
internal const val MaxWindowsMpvChannelLayoutLength = 64
private val windowsMpvAnsiEscapePattern = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")

internal fun stripWindowsMpvLogControls(value: String): String =
    value
        .take(MaxWindowsMpvLogMessageLength)
        .replace(windowsMpvAnsiEscapePattern, "")
        .filterNot { character ->
            character.code < 0x20 ||
                character.code in 0x7F..0x9F ||
                Character.getType(character) == Character.FORMAT.toInt()
        }

internal data class WindowsMpvTrackMetadata(
    val id: String,
    val title: String,
    val language: String,
    val codec: String,
)

internal fun windowsMpvTrackMetadataForModel(
    id: String?,
    title: String?,
    language: String?,
    codec: String?,
): WindowsMpvTrackMetadata = WindowsMpvTrackMetadata(
    id = id.orEmpty().takeIf { value -> value.toLongOrNull() != null }.orEmpty(),
    title = title.orEmpty().take(MaxWindowsMpvTrackTitleLength),
    language = language.orEmpty().take(MaxWindowsMpvTrackLanguageLength),
    codec = codec.orEmpty().take(MaxWindowsMpvTrackCodecLength),
)

internal fun boundWindowsMpvLogMessage(value: String): String =
    value.take(MaxWindowsMpvLogMessageLength)

internal fun desktopPlayerTrace(message: String) {
    desktopPlayerLog.i { boundWindowsMpvLogMessage(redactMpvLogText(message)) }
}

internal fun redactDesktopPlaybackUrlForLog(url: String?): String =
    url.orEmpty().let { value ->
        runCatching { URI(value) }
            .getOrNull()
            ?.let { uri ->
                val host = uri.host?.takeIf { it.isNotBlank() } ?: return@let null
                val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: return@let host
                "$scheme://$host"
            }
            ?.takeIf { it.isNotBlank() }
            ?.take(MaxWindowsMpvMetadataFieldLength)
            ?: "<unknown>"
    }

internal fun playbackUrlForLog(url: String?): String = redactDesktopPlaybackUrlForLog(url)

internal fun playbackMetadataForLog(value: String?): String =
    value.orEmpty()
        .let(::stripWindowsMpvLogControls)
        .let(::redactMpvLogText)
        .trim()
        .take(MaxWindowsMpvMetadataFieldLength)

private val mpvUrlPattern = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)

internal fun redactMpvLogText(value: String): String =
    mpvUrlPattern.replace(stripWindowsMpvLogControls(value)) { match ->
        playbackUrlForLog(match.value)
    }.take(MaxWindowsMpvLogMessageLength)

internal fun traceMpvLogMessage(message: MpvClientEvent.LogMessage) {
    val error = message.decodeError
    if (error == null) {
        desktopPlayerTrace(
            "mpv log level=${playbackMetadataForLog(message.level)} " +
                "prefix=${playbackMetadataForLog(message.prefix)} " +
                "text=${playbackMetadataForLog(message.text)}",
        )
    } else {
        desktopPlayerTrace(
            "mpv log decode failed type=${playbackMetadataForLog(error::class.simpleName)} " +
                "message=${playbackMetadataForLog(error.message)}",
        )
    }
}
