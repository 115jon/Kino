package com.nuvio.app.features.streams

import com.nuvio.app.core.build.AppFeaturePolicy

object StreamAutoPlaySelector {

    fun orderAddonStreams(
        groups: List<AddonStreamGroup>,
        installedOrder: List<String>,
    ): List<AddonStreamGroup> {
        if (groups.isEmpty()) return groups

        val addonRankByName = HashMap<String, Int>(installedOrder.size)
        installedOrder.forEachIndexed { index, addonName ->
            if (addonName !in addonRankByName) {
                addonRankByName[addonName] = index
            }
        }

        val (directDebridEntries, remainingEntries) = groups.partition { group ->
            group.addonId.startsWith("debrid:") ||
                group.streams.any { stream -> stream.isAddonDebridCandidate && stream.isDirectDebridStream }
        }
        if (installedOrder.isEmpty()) return directDebridEntries + remainingEntries

        val (addonEntries, pluginEntries) = remainingEntries.partition { group ->
            group.addonName in addonRankByName
        }
        val orderedAddons = addonEntries.sortedBy { group ->
            addonRankByName.getValue(group.addonName)
        }
        return directDebridEntries + orderedAddons + pluginEntries
    }

    fun selectAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
    ): StreamItem? =
        evaluateAutoPlayStream(
            streams = streams,
            mode = mode,
            regexPattern = regexPattern,
            source = source,
            installedAddonNames = installedAddonNames,
            selectedAddons = selectedAddons,
            selectedPlugins = selectedPlugins,
            preferredBingeGroup = preferredBingeGroup,
            preferBingeGroupInSelection = preferBingeGroupInSelection,
            bingeGroupOnly = bingeGroupOnly,
            debridEnabled = debridEnabled,
            activeResolverProviderId = activeResolverProviderId,
        ).stream

    fun evaluateAutoPlayStream(
        streams: List<StreamItem>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null,
        preferBingeGroupInSelection: Boolean = false,
        bingeGroupOnly: Boolean = false,
        debridEnabled: Boolean = true,
        activeResolverProviderId: String? = null,
    ): StreamAutoPlayEvaluation {
        if (streams.isEmpty()) return StreamAutoPlayEvaluation()

        val sourceScopedStreams = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return StreamAutoPlayEvaluation()
        if (mode == StreamAutoPlayMode.MANUAL && !bingeGroupOnly) {
            return StreamAutoPlayEvaluation()
        }

        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        val bingeGroupCandidates = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            candidateStreams.filter { stream -> stream.behaviorHints.bingeGroup == targetBingeGroup }
        } else {
            emptyList()
        }
        val preferredReadyStream = bingeGroupCandidates
            .orderedForAutoPlay()
            .firstOrNull { stream ->
            stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
            }
        if (bingeGroupOnly) {
            val readyStreams = preferredReadyStream?.let(::listOf).orEmpty()
            return StreamAutoPlayEvaluation(
                stream = preferredReadyStream,
                readyStreams = readyStreams,
                hasPendingDebridCandidate = preferredReadyStream == null &&
                    bingeGroupCandidates.any {
                        it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
                    },
            )
        }
        if (mode == StreamAutoPlayMode.MANUAL) {
            return StreamAutoPlayEvaluation()
        }
        val preferredStream = if (preferBingeGroupInSelection && targetBingeGroup.isNotEmpty()) {
            bingeGroupCandidates
                .orderedForAutoPlay()
                .firstOrNull { stream ->
                    stream.isAutoPlayable(debridEnabled, activeResolverProviderId)
                }
        } else {
            null
        }
        val matchingStreams = when (mode) {
            StreamAutoPlayMode.MANUAL -> emptyList()
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()

                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
                    ?: return StreamAutoPlayEvaluation()

                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex("\\b(${exclusionWords.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
                } else null

                candidateStreams.filter { stream ->
                    val url = stream.playableDirectUrl.orEmpty()

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.streamLabel).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(url)
                    }

                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }
            }
        }
        if (matchingStreams.isEmpty() && preferredStream == null) return StreamAutoPlayEvaluation()

        val readyStreams = buildList {
            preferredStream?.let(::add)
            matchingStreams
                .orderedForAutoPlay()
                .filter { it.isAutoPlayable(debridEnabled, activeResolverProviderId) }
                .filterNot { it == preferredStream }
                .forEach(::add)
        }
        val selected = readyStreams.firstOrNull()
        if (selected != null) {
            return StreamAutoPlayEvaluation(
                stream = selected,
                readyStreams = readyStreams,
            )
        }

        return StreamAutoPlayEvaluation(
            readyStreams = readyStreams,
            hasPendingDebridCandidate = matchingStreams.any {
                it.isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            },
        )
    }

    private fun List<StreamItem>.orderedForAutoPlay(): List<StreamItem> {
        if (size < 2 || none { it.hasExplicitAudioMetadata() || it.hasExplicitVideoMetadata() }) return this
        return groupBy { Triple(it.addonId, it.addonName, it.sourceName.orEmpty()) }
            .values
            .flatMap { group ->
                if (group.size < 2 || group.none { it.hasExplicitAudioMetadata() || it.hasExplicitVideoMetadata() }) {
                    group
                } else {
                    group.map { it.explicitVideoRank() }
                        .distinct()
                        .sortedDescending()
                        .flatMap { videoRank ->
                            group.filter { it.explicitVideoRank() == videoRank }
                                .sortedByDescending { it.explicitAudioRank() }
                        }
                }
            }
    }

    private fun StreamItem.hasExplicitVideoMetadata(): Boolean =
        clientResolve?.stream?.raw?.parsed?.let { parsed ->
            !parsed.resolution.isNullOrBlank() || !parsed.quality.isNullOrBlank()
        } == true

    private fun StreamItem.hasExplicitAudioMetadata(): Boolean =
        clientResolve?.stream?.raw?.parsed?.let { parsed ->
            parsed.audio.any { it.isNotBlank() } || parsed.channels.any { it.isNotBlank() }
        } == true

    private fun StreamItem.explicitVideoRank(): Int {
        val parsed = clientResolve?.stream?.raw?.parsed ?: return 0
        val resolutionRank = when {
            parsed.resolution.hasToken("2160", "4k", "uhd") -> 5
            parsed.resolution.hasToken("1440", "2k") -> 4
            parsed.resolution.hasToken("1080", "fhd") -> 3
            parsed.resolution.hasToken("720", "hd") -> 2
            parsed.resolution.hasToken("576", "480", "sd") -> 1
            else -> 0
        }
        val qualityRank = when {
            parsed.quality.hasToken("remux") -> 5
            parsed.quality.hasToken("bluray", "blu-ray") -> 4
            parsed.quality.hasToken("web-dl", "webdl") -> 3
            parsed.quality.hasToken("webrip") -> 2
            parsed.quality.hasToken("hdtv") -> 1
            else -> 0
        }
        return resolutionRank * 10 + qualityRank
    }

    private fun StreamItem.explicitAudioRank(): Int {
        val parsed = clientResolve?.stream?.raw?.parsed ?: return 0
        val audioText = parsed.audio.joinToString(" ").lowercase()
        val channelText = parsed.channels.joinToString(" ").lowercase()
        val codecRank = when {
            audioText.hasToken("truehd", "true hd") -> 100
            audioText.hasToken("flac") -> 95
            audioText.hasToken("dts-hd ma", "dtshd ma") -> 90
            audioText.hasToken("dts:x", "dtsx") -> 85
            audioText.hasToken("dts-hd", "dtshd") -> 80
            audioText.hasToken("dts") -> 70
            audioText.hasToken("dd+", "ddp", "eac3", "e-ac-3", "eac-3") -> 60
            audioText.hasToken("dd", "ac3") -> 50
            audioText.hasToken("opus") -> 40
            audioText.hasToken("atmos") -> 35
            audioText.hasToken("aac") -> 30
            else -> 0
        }
        val channelRank = when {
            channelText.hasToken("7.1", "7ch", "8ch") -> 80
            channelText.hasToken("5.1", "6ch") -> 60
            channelText.hasToken("2.0", "stereo", "2ch") -> 20
            else -> 0
        }
        val atmosRank = if (audioText.hasToken("atmos")) 1 else 0
        return codecRank * 1_000 + channelRank * 10 + atmosRank
    }

    private fun String?.hasToken(vararg values: String): Boolean {
        return values.any { value -> matchesStreamMetadataToken(this, value) }
    }

    private fun StreamItem.isAutoPlayable(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean =
        playableDirectUrl != null ||
            (
                AppFeaturePolicy.p2pEnabled &&
                    needsLocalDebridResolve &&
                    p2pInfoHash != null &&
                    !isPendingDebridAutoPlay(debridEnabled, activeResolverProviderId)
            ) ||
            (debridEnabled && isAddonDebridCandidate && isReadyDebridAutoPlay(activeResolverProviderId))

    private fun StreamItem.isReadyDebridAutoPlay(activeResolverProviderId: String?): Boolean =
        when {
            isDirectDebridStream -> clientResolve?.service.matchesResolver(activeResolverProviderId)
            isCachedDebridTorrentStream -> debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)
            else -> false
        }

    private fun StreamItem.isPendingDebridAutoPlay(
        debridEnabled: Boolean,
        activeResolverProviderId: String?,
    ): Boolean {
        if (!debridEnabled || !isInstalledAddonStream || !needsLocalDebridResolve) return false
        if (!debridCacheStatus?.providerId.matchesResolver(activeResolverProviderId)) return false
        val state = debridCacheStatus?.state
        return state == null || state == StreamDebridCacheState.CHECKING
    }

    private fun String?.matchesResolver(activeResolverProviderId: String?): Boolean {
        val active = activeResolverProviderId?.trim().orEmpty()
        return active.isBlank() || this == null || equals(active, ignoreCase = true)
    }
}

internal const val MaxStreamMetadataTokenInputLength = 4_096
private const val MaxStreamMetadataNegationWindowLength = 96

private fun isStreamMetadataDash(codePoint: Int): Boolean =
    codePoint == 0x002D ||
        codePoint == 0x00AD ||
        codePoint == 0x058A ||
        codePoint == 0x05BE ||
        codePoint == 0x1400 ||
        codePoint == 0x1806 ||
        codePoint in 0x2010..0x2015 ||
        codePoint == 0x2E17 ||
        codePoint == 0x2E1A ||
        codePoint in 0x2E3A..0x2E3B ||
        codePoint == 0x2E40 ||
        codePoint == 0x301C ||
        codePoint == 0x3030 ||
        codePoint == 0x30A0 ||
        codePoint in 0xFE31..0xFE32 ||
        codePoint == 0xFE58 ||
        codePoint == 0xFE63 ||
        codePoint == 0xFF0D ||
        codePoint == 0x10D6E ||
        codePoint == 0x10EAD

private fun normalizeStreamMetadataText(value: String): String = buildString(value.length) {
    val normalized = value.lowercase()
    var index = 0
    while (index < normalized.length) {
        val first = normalized[index]
        val hasSurrogatePair = first in '\uD800'..'\uDBFF' &&
            index + 1 < normalized.length &&
            normalized[index + 1] in '\uDC00'..'\uDFFF'
        val codePoint = if (hasSurrogatePair) {
            0x10000 +
                ((first.code - 0xD800) shl 10) +
                (normalized[index + 1].code - 0xDC00)
        } else {
            first.code
        }
        val isUnpairedSurrogate = !hasSurrogatePair && first in '\uD800'..'\uDFFF'
        if (isUnpairedSurrogate || isStreamMetadataDash(codePoint)) {
            append('-')
        } else if (codePoint <= 0xFFFF) {
            append(codePoint.toChar())
        } else {
            val supplementary = codePoint - 0x10000
            append((0xD800 + (supplementary shr 10)).toChar())
            append((0xDC00 + (supplementary and 0x3FF)).toChar())
        }
        index += if (hasSurrogatePair) 2 else 1
    }
}

internal fun matchesStreamMetadataToken(value: String?, token: String): Boolean {
    val normalizedValue = value?.take(MaxStreamMetadataTokenInputLength)?.let(::normalizeStreamMetadataText).orEmpty()
    val normalizedToken = normalizeStreamMetadataText(token.take(MaxStreamMetadataTokenInputLength))
    if (normalizedToken.isEmpty()) return false
    val numericResolutionSuffix = if (normalizedToken.all { it.isDigit() }) "p?" else ""
    var searchStart = 0
    while (searchStart <= normalizedValue.length - normalizedToken.length) {
        val tokenStart = normalizedValue.indexOf(normalizedToken, searchStart)
        if (tokenStart < 0) return false
        val beforeMatches = tokenStart == 0 || !normalizedValue[tokenStart - 1].isLetterOrDigit()
        var tokenEnd = tokenStart + normalizedToken.length
        if (numericResolutionSuffix == "p?" && normalizedValue.getOrNull(tokenEnd) == 'p') {
            tokenEnd += 1
        }
        val afterMatches = tokenEnd >= normalizedValue.length || !normalizedValue[tokenEnd].isLetterOrDigit()
        if (beforeMatches && afterMatches) {
            val preceding = normalizedValue.substring(0, tokenStart).takeLast(MaxStreamMetadataNegationWindowLength)
            val negated = Regex(
                "(?:not|without|no)(?:[-\\s]+(?:a|an))?[-\\s]*(?:[\\p{L}\\p{N}]+[-\\s]+){0,3}$|non[-\\s]*$",
            ).containsMatchIn(preceding)
            if (!negated) return true
        }
        searchStart = tokenStart + 1
    }
    return false
}

data class StreamAutoPlayEvaluation(
    val stream: StreamItem? = null,
    val readyStreams: List<StreamItem> = emptyList(),
    val hasPendingDebridCandidate: Boolean = false,
)
