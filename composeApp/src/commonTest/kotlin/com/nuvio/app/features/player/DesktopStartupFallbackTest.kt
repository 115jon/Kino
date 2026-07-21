package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamClientResolveRaw
import com.nuvio.app.features.streams.StreamClientResolveStream
import com.nuvio.app.features.streams.StreamClientResolveParsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class DesktopStartupFallbackTest {

    @Test
    fun fourKFailureSelectsNextFourKCandidateOnly() {
        val first = candidate("first", StartupFallbackTier.FourKPlus)
        val second = candidate("second", StartupFallbackTier.FourKPlus)
        val state = started(first, second, candidate("lower", StartupFallbackTier.P1440))

        val result = state.reduceFailure(first)

        assertEquals("second", assertIs<StartupFallbackDecision.Attempt>(result.decision).candidate.identityKey)
    }

    @Test
    fun fourKFailureDoesNotAutoSelectLowerOrUnknownCandidates() {
        val first = candidate("first", StartupFallbackTier.FourKPlus)
        val lower = candidate("lower", StartupFallbackTier.P1440)
        val unknown = candidate("unknown", StartupFallbackTier.Unknown)
        val state = started(first, lower, unknown)

        val result = state.reduceFailure(first)

        assertEquals(lower, assertIs<StartupFallbackDecision.RequestConsent>(result.decision).candidate)
        assertEquals(first.identityKey, result.state.currentIdentityKey)
    }

    @Test
    fun lowerTierDecisionRequestsConsentAndDenialDoesNotAttempt() {
        val first = candidate("first", StartupFallbackTier.P1080)
        val lower = candidate("lower", StartupFallbackTier.P720)
        val state = started(first, lower)

        val consent = state.reduceFailure(first)
        val denied = consent.state.reduce(
            StartupFallbackEvent.ConsentDenied(
                sessionGeneration = 1L,
                identityKey = lower.identityKey,
            ),
        )

        assertIs<StartupFallbackDecision.RequestConsent>(consent.decision)
        assertIs<StartupFallbackDecision.Ignore>(denied.decision)
        assertEquals(1, denied.state.attemptedIdentityKeys.size)
        assertFalse(denied.state.pendingConsentIdentityKey != null)
        assertEquals(true, denied.state.invalidated)
    }

    @Test
    fun lowerTierApprovalAttemptsOnce() {
        val first = candidate("first", StartupFallbackTier.P1080)
        val lower = candidate("lower", StartupFallbackTier.P720)
        val consent = started(first, lower).reduceFailure(first)

        val approved = consent.state.reduce(
            StartupFallbackEvent.ConsentGranted(
                sessionGeneration = 1L,
                identityKey = lower.identityKey,
            ),
        )
        val repeated = approved.state.reduce(
            StartupFallbackEvent.ConsentGranted(
                sessionGeneration = 1L,
                identityKey = lower.identityKey,
            ),
        )

        assertEquals("lower", assertIs<StartupFallbackDecision.Attempt>(approved.decision).candidate.identityKey)
        assertIs<StartupFallbackDecision.Ignore>(repeated.decision)
        assertEquals(2, approved.state.attemptedIdentityKeys.size)
    }

    @Test
    fun sameTier1080FallbackWorks() {
        val first = candidate("first", StartupFallbackTier.P1080)
        val second = candidate("second", StartupFallbackTier.P1080)

        val result = started(first, second).reduceFailure(first)

        assertEquals("second", assertIs<StartupFallbackDecision.Attempt>(result.decision).candidate.identityKey)
    }

    @Test
    fun unknownCandidateIsNeverAutomaticallyAttempted() {
        val first = candidate("first", StartupFallbackTier.Unknown)
        val second = candidate("second", StartupFallbackTier.Unknown)

        val result = started(first, second).reduceFailure(first)

        assertIs<StartupFallbackDecision.Ignore>(result.decision)
    }

    @Test
    fun loadedMediaAndStaleOrCancelledEventsAreIgnored() {
        val first = candidate("first", StartupFallbackTier.P1080)
        val second = candidate("second", StartupFallbackTier.P1080)
        val state = started(first, second)

        val loaded = state.reduce(StartupFallbackEvent.MediaLoaded)
        val afterLoaded = loaded.state.reduceFailure(first)
        val stale = state.reduceFailure(
            candidate = first,
            sessionGeneration = 2L,
        )
        val staleAttempt = state.reduce(
            StartupFallbackEvent.CandidateFailed(
                sessionGeneration = 1L,
                attemptGeneration = 2L,
                identityKey = first.identityKey,
            ),
        )
        val cancelled = state.reduce(StartupFallbackEvent.Cancelled)
            .state
            .reduceFailure(first)
        val manuallyChanged = state.reduce(StartupFallbackEvent.ManualSourceChanged(1L))
            .state
            .reduceFailure(first)

        assertIs<StartupFallbackDecision.Ignore>(afterLoaded.decision)
        assertIs<StartupFallbackDecision.Ignore>(stale.decision)
        assertIs<StartupFallbackDecision.Ignore>(staleAttempt.decision)
        assertIs<StartupFallbackDecision.Ignore>(cancelled.decision)
        assertIs<StartupFallbackDecision.Ignore>(manuallyChanged.decision)
    }

    @Test
    fun duplicateIdentitiesAreAttemptedOnceInOriginalOrder() {
        val first = candidate("first", StartupFallbackTier.P1080)
        val duplicate = candidate("first", StartupFallbackTier.P1080, url = "https://other.example/video")
        val second = candidate("second", StartupFallbackTier.P1080)
        val third = candidate("third", StartupFallbackTier.P1080)
        val state = started(first, duplicate, second, third)

        val afterFirst = state.reduceFailure(first)
        val afterSecond = afterFirst.state.reduceFailure(second)

        assertEquals("second", assertIs<StartupFallbackDecision.Attempt>(afterFirst.decision).candidate.identityKey)
        assertEquals("third", assertIs<StartupFallbackDecision.Attempt>(afterSecond.decision).candidate.identityKey)
        assertEquals(listOf("first", "second", "third"), afterSecond.state.candidates.map { it.identityKey })
    }

    @Test
    fun unknownCandidatesAreFilteredBeforeFallbackCoordination() {
        val first = candidate("first", StartupFallbackTier.FourKPlus)
        val unknown = candidate("unknown", StartupFallbackTier.Unknown)
        val lower = candidate("lower", StartupFallbackTier.P1440)

        val state = started(first, unknown, lower)

        assertEquals(listOf("first", "lower"), state.candidates.map { it.identityKey })
    }

    @Test
    fun commonResolutionLabelsClassifyAsKnownTier() {
        assertEquals(StartupFallbackTier.FourKPlus, startupFallbackTier(streamWithResolution("2160p")))
        assertEquals(StartupFallbackTier.FourKPlus, startupFallbackTier(streamWithResolution("4K")))
        assertEquals(StartupFallbackTier.FourKPlus, startupFallbackTier(streamWithResolution("UHD")))
        assertEquals(StartupFallbackTier.Unknown, startupFallbackTier(streamWithResolution("2160")))
        assertEquals(StartupFallbackTier.P1080, startupFallbackTier(streamWithResolution("1920x1080")))
        assertEquals(StartupFallbackTier.Unknown, startupFallbackTier(streamWithResolution("99999p")))
        assertEquals(StartupFallbackTier.Unknown, startupFallbackTier(streamWithResolution("1080 or 2160p")))
    }

    @Test
    fun sameTierCandidateAfterLowerTierCandidateIsStillSelected() {
        val first = candidate("first", StartupFallbackTier.FourKPlus)
        val lower = candidate("lower", StartupFallbackTier.P1080)
        val sameTier = candidate("same-tier", StartupFallbackTier.FourKPlus)

        val result = started(first, lower, sameTier).reduceFailure(first)

        assertEquals("same-tier", assertIs<StartupFallbackDecision.Attempt>(result.decision).candidate.identityKey)
    }

    @Test
    fun unknownCandidateDoesNotBlockKnownLowerTierConsent() {
        val first = candidate("first", StartupFallbackTier.FourKPlus)
        val unknown = candidate("unknown", StartupFallbackTier.Unknown)
        val lower = candidate("lower", StartupFallbackTier.P1080)

        val result = started(first, unknown, lower).reduceFailure(first)

        assertEquals(lower, assertIs<StartupFallbackDecision.RequestConsent>(result.decision).candidate)
    }

    @Test
    fun fallbackCandidatesKeepOnlyDirectHttpSourcesAndStableOrder() {
        val first = candidateStream("first", "https://example.com/first")
        val duplicate = first.copy(name = "first")
        val second = candidateStream("second", "http://example.com/second")
        val unsupported = candidateStream("unsupported", "ftp://example.com/file")
        val external = candidateStream("external", "https://example.com/external")
            .copy(url = null, externalUrl = "https://external.example/watch")

        val candidates = startupFallbackCandidates(listOf(first, duplicate, unsupported, external, second))

        assertEquals(
            listOf("https://example.com/first", "http://example.com/second"),
            candidates.map { it.stream.playableDirectUrl },
        )
    }

    @Test
    fun sameUrlSourcesHaveDistinctReloadIdentities() {
        val first = candidateStream("first", "https://example.com/video")
        val second = first.copy(name = "second")

        val identities = startupFallbackCandidates(listOf(first, second)).map { it.identityKey }

        assertEquals(2, identities.size)
        assertNotEquals(identities[0], identities[1])
    }

    @Test
    fun directSourcesIncludeProviderMetadataInSameUrlIdentity() {
        val first = candidateStream("first", "https://example.com/video").copy(clientResolve = null)
        val second = first.copy(name = "second")

        assertNotEquals(first.playerSourceIdentityKey(), second.playerSourceIdentityKey())
    }

    @Test
    fun onlyWindowsIsAValidatedStartupFallbackPlatform() {
        assertEquals(true, isValidatedStartupFallbackOs("Windows 11"))
        assertEquals(false, isValidatedStartupFallbackOs("Mac OS X"))
        assertEquals(false, isValidatedStartupFallbackOs("Darwin"))
        assertEquals(false, isValidatedStartupFallbackOs("Linux"))
    }

    @Test
    fun staleSameUrlSurfaceCallbacksAreRejectedByAttemptToken() {
        assertEquals(
            true,
            isCurrentPlayerSurfaceAttempt(
                currentSurfaceSourceUrl = "https://example.com/video",
                surfaceSourceUrl = "https://example.com/video",
                currentSourceIdentityKey = "source:latest",
                surfaceSourceIdentityKey = "source:latest",
                currentAttemptToken = 8L,
                surfaceAttemptToken = 8L,
            ),
        )
        assertEquals(
            false,
            isCurrentPlayerSurfaceAttempt(
                currentSurfaceSourceUrl = "https://example.com/video",
                surfaceSourceUrl = "https://example.com/video",
                currentSourceIdentityKey = "source:latest",
                surfaceSourceIdentityKey = "source:latest",
                currentAttemptToken = 8L,
                surfaceAttemptToken = 7L,
            ),
        )
    }

    private fun started(vararg candidates: StartupFallbackCandidate): StartupFallbackState =
        StartupFallbackCoordinator.start(
            sessionGeneration = 1L,
            candidates = candidates.toList(),
            initialIdentityKey = candidates.first().identityKey,
        )

    private fun StartupFallbackState.reduceFailure(
        candidate: StartupFallbackCandidate,
        sessionGeneration: Long = this.sessionGeneration,
    ): StartupFallbackReduction = reduce(
        StartupFallbackEvent.CandidateFailed(
            sessionGeneration = sessionGeneration,
            attemptGeneration = activeAttemptGeneration,
            identityKey = candidate.identityKey,
        ),
    )

    private fun candidate(
        identityKey: String,
        tier: StartupFallbackTier,
        url: String = "https://example.com/$identityKey.m3u8",
    ) = StartupFallbackCandidate(
        stream = candidateStream(identityKey, url),
        identityKey = identityKey,
        tier = tier,
    )

    private fun candidateStream(name: String, url: String) = StreamItem(
        name = name,
        url = url,
        addonName = "Test addon",
        addonId = "test-addon",
        clientResolve = StreamClientResolve(
            stream = StreamClientResolveStream(
                raw = StreamClientResolveRaw(
                    parsed = StreamClientResolveParsed(resolution = "1080p"),
                ),
            ),
        ),
    )

    private fun streamWithResolution(resolution: String) = candidateStream("stream", "https://example.com/stream")
        .copy(
            clientResolve = StreamClientResolve(
                stream = StreamClientResolveStream(
                    raw = StreamClientResolveRaw(
                        parsed = StreamClientResolveParsed(resolution = resolution),
                    ),
                ),
            ),
        )
}
