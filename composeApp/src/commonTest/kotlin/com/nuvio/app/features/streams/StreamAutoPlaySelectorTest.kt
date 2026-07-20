package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamAutoPlaySelectorTest {

    @Test
    fun `bingeGroup-first selects matching stream before first stream mode`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "1080p",
            bingeGroup = "other-group",
        )
        val preferred = stream(
            addonName = "AddonB",
            url = "https://example.com/preferred.m3u8",
            name = "720p",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, preferred),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(preferred, selected)
    }

    @Test
    fun `normal autoplay uses ordered ready candidate inside preferred binge group`() {
        val lossy = stream(
            addonName = "AddonA",
            url = "https://example.com/eac3.mkv",
            name = "1080p E-AC-3",
            bingeGroup = "same-group",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                audio = listOf("E-AC-3"),
            ),
        )
        val lossless = stream(
            addonName = "AddonA",
            url = "https://example.com/truehd.mkv",
            name = "1080p TrueHD",
            bingeGroup = "same-group",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                audio = listOf("TrueHD"),
            ),
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(lossy, lossless),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(lossless, selected)
    }

    @Test
    fun `ordered autoplay ranks explicit video quality above unknown metadata`() {
        val unknown = stream(
            addonName = "AddonA",
            url = "https://example.com/unknown.mkv",
            name = "Unknown",
        )
        val explicit1080 = stream(
            addonName = "AddonA",
            url = "https://example.com/1080.mkv",
            name = "1080p",
            parsed = StreamClientResolveParsed(resolution = "1080p"),
        )
        val explicit2160 = stream(
            addonName = "AddonA",
            url = "https://example.com/2160.mkv",
            name = "2160p",
            parsed = StreamClientResolveParsed(resolution = "2160p"),
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(unknown, explicit1080, explicit2160),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(explicit2160, selected)
    }

    @Test
    fun `autoplay sorts within original addon source groups without cross-addon spoofing`() {
        val addonAAtmos = stream(
            addonName = "AddonA",
            url = "https://example.com/a-atmos.mkv",
            name = "1080p Atmos",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                audio = listOf("Atmos"),
            ),
        ).copy(sourceName = "source-a")
        val addonATrueHd = stream(
            addonName = "AddonA",
            url = "https://example.com/a-truehd.mkv",
            name = "1080p TrueHD",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                audio = listOf("TrueHD"),
            ),
        ).copy(sourceName = "source-a")
        val spoofedAddonB = stream(
            addonName = "AddonB",
            url = "https://example.com/b-spoofed.mkv",
            name = "1080p notremux",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                quality = "notremux",
                audio = listOf("AAC"),
            ),
        ).copy(sourceName = "source-b")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(addonAAtmos, addonATrueHd, spoofedAddonB),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(addonATrueHd, selected)
    }

    @Test
    fun `duplicate addon names do not merge quality groups`() {
        val firstAddon = stream(
            addonName = "Same Name",
            url = "https://example.com/first.mkv",
            name = "1080p AAC",
            parsed = StreamClientResolveParsed(resolution = "1080p", audio = listOf("AAC")),
        ).copy(addonId = "addon:first")
        val secondAddon = stream(
            addonName = "Same Name",
            url = "https://example.com/second.mkv",
            name = "2160p TrueHD",
            parsed = StreamClientResolveParsed(resolution = "2160p", audio = listOf("TrueHD")),
        ).copy(addonId = "addon:second")

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(firstAddon, secondAddon),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Same Name"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(firstAddon, selected)
    }

    @Test
    fun `falls back to normal mode when no bingeGroup match exists`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            name = "First",
            bingeGroup = "group-a",
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            name = "Second",
            bingeGroup = "group-b",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "missing-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(first, selected)
    }

    @Test
    fun `bingeGroup-first respects source and addon plugin filters`() {
        val filteredOutAddonMatch = stream(
            addonName = "AddonFilteredOut",
            url = "https://example.com/addon-match.m3u8",
            bingeGroup = "same-group",
        )
        val allowedPluginMatch = stream(
            addonName = "PluginAllowed",
            url = "https://example.com/plugin-match.m3u8",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(filteredOutAddonMatch, allowedPluginMatch),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ENABLED_PLUGINS_ONLY,
            installedAddonNames = setOf("AddonFilteredOut"),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("PluginAllowed"),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertEquals(allowedPluginMatch, selected)
    }

    @Test
    fun `blank preferredBingeGroup behaves as disabled`() {
        val first = stream(
            addonName = "AddonA",
            url = "https://example.com/first.m3u8",
            bingeGroup = "group-a",
        )
        val second = stream(
            addonName = "AddonB",
            url = "https://example.com/second.m3u8",
            bingeGroup = "group-b",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(first, second),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "   ",
            preferBingeGroupInSelection = true,
        )

        assertEquals(first, selected)
    }

    @Test
    fun `manual mode remains manual even with matching bingeGroup`() {
        val matched = stream(
            addonName = "AddonA",
            url = "https://example.com/match.m3u8",
            bingeGroup = "same-group",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(matched),
            mode = StreamAutoPlayMode.MANUAL,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
        )

        assertNull(selected)
    }

    @Test
    fun `first stream mode can select direct debrid candidate without resolved URL`() {
        val directDebrid = stream(
            addonName = "Torbox Instant",
            url = null,
            name = "TB Instant",
            directDebrid = true,
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(directDebrid),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(directDebrid, selected)
    }

    @Test
    fun `first stream mode does not auto select external url browser link`() {
        val external = stream(
            addonName = "External Addon",
            externalUrl = "https://example.com/watch",
            name = "Watch on site",
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(external),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("External Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertNull(selected)
    }

    @Test
    fun `timeout evaluation keeps pending regex debrid candidate open`() {
        val pending = stream(
            addonName = "Torrentio",
            name = "The Show 1080p",
            infoHash = "hash-pending",
            cacheState = StreamDebridCacheState.CHECKING,
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(pending),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertNull(evaluation.stream)
        assertTrue(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `timeout evaluation still selects direct link while debrid candidate is pending`() {
        val pending = stream(
            addonName = "Torrentio",
            name = "The Show 1080p",
            infoHash = "hash-pending",
            cacheState = StreamDebridCacheState.CHECKING,
        )
        val direct = stream(
            addonName = "Direct Addon",
            url = "https://example.com/video.mp4",
            name = "The Show 1080p",
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(pending, direct),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "1080p",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Torrentio", "Direct Addon"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertEquals(direct, evaluation.stream)
        assertFalse(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `direct debrid candidate must match active resolver`() {
        val torbox = stream(
            addonName = "Comet",
            name = "TB Instant",
            directDebrid = true,
            directDebridService = "torbox",
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(torbox),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("Comet"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            debridEnabled = true,
            activeResolverProviderId = "premiumize",
        )

        assertNull(evaluation.stream)
        assertFalse(evaluation.hasPendingDebridCandidate)
    }

    @Test
    fun `first stream mode prefers explicit audio fidelity after video quality`() {
        val highVideo = stream(
            addonName = "AddonA",
            url = "https://example.com/2160.mkv",
            name = "2160p",
            parsed = StreamClientResolveParsed(
                resolution = "2160p",
                audio = listOf("AAC"),
            ),
        )
        val highAudio = stream(
            addonName = "AddonA",
            url = "https://example.com/1080.mkv",
            name = "1080p",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                audio = listOf("Atmos"),
                channels = listOf("7.1"),
            ),
        )
        val sameVideoHigherAudio = stream(
            addonName = "AddonA",
            url = "https://example.com/2160-atmos.mkv",
            name = "2160p Atmos",
            parsed = StreamClientResolveParsed(
                resolution = "2160p",
                audio = listOf("Atmos"),
                channels = listOf("7.1"),
            ),
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(highVideo, highAudio, sameVideoHigherAudio),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(sameVideoHigherAudio, selected)
    }

    @Test
    fun `unknown audio metadata remains eligible for regex selection`() {
        val unknown = stream(
            addonName = "AddonA",
            url = "https://example.com/unknown.mkv",
            name = "The Show",
        )
        val explicit = stream(
            addonName = "AddonA",
            url = "https://example.com/explicit.mkv",
            name = "The Show",
            parsed = StreamClientResolveParsed(audio = listOf("AAC")),
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(unknown, explicit),
            mode = StreamAutoPlayMode.REGEX_MATCH,
            regexPattern = "The Show",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertTrue(evaluation.readyStreams.contains(unknown))
        assertTrue(evaluation.readyStreams.contains(explicit))
        assertEquals(explicit, evaluation.stream)
    }

    @Test
    fun `higher explicit video quality outranks lower video quality`() {
        val firstVideo = stream(
            addonName = "AddonA",
            url = "https://example.com/1080-atmos.mkv",
            name = "1080p Atmos",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                audio = listOf("Atmos"),
            ),
        )
        val secondVideo = stream(
            addonName = "AddonA",
            url = "https://example.com/2160-aac.mkv",
            name = "2160p AAC",
            parsed = StreamClientResolveParsed(
                resolution = "2160p",
                audio = listOf("AAC"),
            ),
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(firstVideo, secondVideo),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(secondVideo, selected)
    }

    @Test
    fun `quality metadata cannot make a later addon outrank an earlier addon`() {
        val trustedAddonStream = stream(
            addonName = "AddonA",
            url = "https://example.com/1080-aac.mkv",
            name = "1080p AAC",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                quality = "WEB-DL",
                audio = listOf("AAC"),
            ),
        )
        val spoofedAddonStream = stream(
            addonName = "AddonB",
            url = "https://example.com/2160-remux-truehd.mkv",
            name = "2160p REMUX TrueHD",
            parsed = StreamClientResolveParsed(
                resolution = "2160p",
                quality = "REMUX",
                audio = listOf("TrueHD"),
            ),
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(trustedAddonStream, spoofedAddonStream),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA", "AddonB"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(trustedAddonStream, selected)
    }

    @Test
    fun `quality matching does not accept embedded spoofed tokens`() {
        assertFalse(matchesStreamMetadataToken("notremux", "remux"))
        assertFalse(matchesStreamMetadataToken("not remux", "remux"))
        assertFalse(matchesStreamMetadataToken("not a remux", "remux"))
        assertFalse(matchesStreamMetadataToken("no TrueHD", "truehd"))
        assertFalse(matchesStreamMetadataToken("without lossless TrueHD", "truehd"))
        assertFalse(matchesStreamMetadataToken("no Dolby Atmos", "atmos"))
        assertFalse(matchesStreamMetadataToken("not-remux", "remux"))
        assertFalse(matchesStreamMetadataToken("no-truehd", "truehd"))
        assertFalse(matchesStreamMetadataToken("without-remux", "remux"))
        assertFalse(matchesStreamMetadataToken("not\u2011remux", "remux"))
        assertFalse(matchesStreamMetadataToken("no\u2014truehd", "truehd"))
        assertFalse(matchesStreamMetadataToken("no\u058Atruehd", "truehd"))
        assertFalse(matchesStreamMetadataToken("no\u00ADtruehd", "truehd"))
        assertFalse(matchesStreamMetadataToken("no\uD800truehd", "truehd"))
        assertFalse(matchesStreamMetadataToken("no\uD803\uDD6Etruehd", "truehd"))
        assertFalse(matchesStreamMetadataToken("no\uD803\uDEADtruehd", "truehd"))
        assertFalse(matchesStreamMetadataToken("non-remux", "remux"))
        assertFalse(matchesStreamMetadataToken("without truehd", "truehd"))
        assertTrue(matchesStreamMetadataToken("2160p WEB-DL", "2160"))
        assertTrue(matchesStreamMetadataToken("2160p WEB-DL", "web-dl"))
        assertFalse(matchesStreamMetadataToken("fakeéremux", "remux"))
        assertFalse(matchesStreamMetadataToken("2160é", "2160"))
        val oversizedMetadata = "x".repeat(MaxStreamMetadataTokenInputLength) + " remux"
        assertTrue(oversizedMetadata.length > MaxStreamMetadataTokenInputLength)
        assertFalse(matchesStreamMetadataToken(oversizedMetadata, "remux"))
    }

    @Test
    fun `lossless codec outranks lossy atmos audio`() {
        val lossyAtmos = stream(
            addonName = "AddonA",
            url = "https://example.com/eac3-atmos.mkv",
            name = "1080p E-AC-3 Atmos",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                audio = listOf("E-AC-3", "Atmos"),
            ),
        )
        val lossless = stream(
            addonName = "AddonA",
            url = "https://example.com/truehd.mkv",
            name = "1080p TrueHD",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                audio = listOf("TrueHD"),
            ),
        )

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(lossyAtmos, lossless),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
        )

        assertEquals(lossless, selected)
    }

    @Test
    fun `binge-only selection also prefers lossless codec`() {
        val lossyAtmos = stream(
            addonName = "AddonA",
            url = "https://example.com/eac3-atmos.mkv",
            name = "1080p E-AC-3 Atmos",
            bingeGroup = "same-group",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                audio = listOf("E-AC-3", "Atmos"),
            ),
        )
        val lossless = stream(
            addonName = "AddonA",
            url = "https://example.com/truehd.mkv",
            name = "1080p TrueHD",
            bingeGroup = "same-group",
            parsed = StreamClientResolveParsed(
                resolution = "1080p",
                audio = listOf("TrueHD"),
            ),
        )

        val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
            streams = listOf(lossyAtmos, lossless),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AddonA"),
            selectedAddons = emptySet(),
            selectedPlugins = emptySet(),
            preferredBingeGroup = "same-group",
            preferBingeGroupInSelection = true,
            bingeGroupOnly = true,
        )

        assertEquals(lossless, evaluation.stream)
    }

    private fun stream(
        addonName: String,
        url: String? = null,
        externalUrl: String? = null,
        name: String? = null,
        bingeGroup: String? = null,
        directDebrid: Boolean = false,
        directDebridService: String = "torbox",
        infoHash: String? = null,
        cacheState: StreamDebridCacheState? = null,
        parsed: StreamClientResolveParsed? = null,
    ): StreamItem = StreamItem(
        name = name,
        url = url,
        externalUrl = externalUrl,
        infoHash = infoHash,
        addonName = addonName,
        addonId = "addon:$addonName",
        clientResolve = if (directDebrid || parsed != null) {
            StreamClientResolve(
                type = if (directDebrid) "debrid" else null,
                service = directDebridService.takeIf { directDebrid },
                isCached = true.takeIf { directDebrid },
                infoHash = "hash".takeIf { directDebrid },
                stream = parsed?.let {
                    StreamClientResolveStream(
                        raw = StreamClientResolveRaw(parsed = it),
                    )
                },
            )
        } else {
            null
        },
        debridCacheStatus = cacheState?.let { state ->
            StreamDebridCacheStatus(
                providerId = "premiumize",
                providerName = "Premiumize",
                state = state,
            )
        },
        behaviorHints = StreamBehaviorHints(
            bingeGroup = bingeGroup,
        ),
    )
}
