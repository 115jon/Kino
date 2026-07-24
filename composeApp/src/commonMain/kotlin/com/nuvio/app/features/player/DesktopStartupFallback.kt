package com.nuvio.app.features.player

import com.nuvio.app.features.streams.StreamItem

internal expect val supportsValidatedStartupFallback: Boolean

enum class StartupFallbackTier(
    val rank: Int,
) {
    Unknown(-1),
    P576OrLower(0),
    P720(1),
    P1080(2),
    P1440(3),
    FourKPlus(4),
}

data class StartupFallbackCandidate(
    val stream: StreamItem,
    val identityKey: String,
    val tier: StartupFallbackTier,
)

internal sealed interface StartupFallbackEvent {
    data class CandidateFailed(
        val sessionGeneration: Long,
        val attemptGeneration: Long,
        val identityKey: String,
    ) : StartupFallbackEvent

    data class ConsentGranted(
        val sessionGeneration: Long,
        val identityKey: String,
    ) : StartupFallbackEvent

    data class ConsentDenied(
        val sessionGeneration: Long,
        val identityKey: String,
    ) : StartupFallbackEvent

    data object MediaLoaded : StartupFallbackEvent

    data object Cancelled : StartupFallbackEvent

    data class ManualSourceChanged(
        val sessionGeneration: Long,
    ) : StartupFallbackEvent
}

internal sealed interface StartupFallbackDecision {
    data class Attempt(
        val candidate: StartupFallbackCandidate,
        val attemptGeneration: Long,
    ) : StartupFallbackDecision

    data class RequestConsent(
        val candidate: StartupFallbackCandidate,
    ) : StartupFallbackDecision

    data object Ignore : StartupFallbackDecision
}

internal data class StartupFallbackState(
    val sessionGeneration: Long,
    val candidates: List<StartupFallbackCandidate>,
    val currentIdentityKey: String?,
    val activeAttemptGeneration: Long,
    val attemptedIdentityKeys: Set<String>,
    val consentDeniedIdentityKeys: Set<String> = emptySet(),
    val pendingConsentIdentityKey: String? = null,
    val mediaLoaded: Boolean = false,
    val invalidated: Boolean = false,
)

internal data class StartupFallbackReduction(
    val state: StartupFallbackState,
    val decision: StartupFallbackDecision,
)

internal object StartupFallbackCoordinator {
    fun start(
        sessionGeneration: Long,
        candidates: List<StartupFallbackCandidate>,
        initialIdentityKey: String?,
    ): StartupFallbackState {
        val deduplicatedCandidates = candidates
            .filter { it.tier != StartupFallbackTier.Unknown }
            .distinctBy { it.identityKey }
        val currentIdentityKey = initialIdentityKey
            ?.takeIf { identity -> deduplicatedCandidates.any { it.identityKey == identity } }
            ?: deduplicatedCandidates.firstOrNull()?.identityKey
        return StartupFallbackState(
            sessionGeneration = sessionGeneration,
            candidates = deduplicatedCandidates,
            currentIdentityKey = currentIdentityKey,
            activeAttemptGeneration = 1L,
            attemptedIdentityKeys = currentIdentityKey?.let(::setOf).orEmpty(),
        )
    }

    fun reduce(
        state: StartupFallbackState,
        event: StartupFallbackEvent,
    ): StartupFallbackReduction {
        return when (event) {
            is StartupFallbackEvent.CandidateFailed -> reduceCandidateFailure(state, event)
            is StartupFallbackEvent.ConsentGranted -> reduceConsentGranted(state, event)
            is StartupFallbackEvent.ConsentDenied -> reduceConsentDenied(state, event)
            StartupFallbackEvent.MediaLoaded -> StartupFallbackReduction(
                state = state.copy(mediaLoaded = true, pendingConsentIdentityKey = null),
                decision = StartupFallbackDecision.Ignore,
            )
            StartupFallbackEvent.Cancelled -> StartupFallbackReduction(
                state = state.copy(invalidated = true, pendingConsentIdentityKey = null),
                decision = StartupFallbackDecision.Ignore,
            )
            is StartupFallbackEvent.ManualSourceChanged -> {
                if (event.sessionGeneration != state.sessionGeneration) {
                    ignored(state)
                } else {
                    StartupFallbackReduction(
                        state = state.copy(invalidated = true, pendingConsentIdentityKey = null),
                        decision = StartupFallbackDecision.Ignore,
                    )
                }
            }
        }
    }

    private fun reduceCandidateFailure(
        state: StartupFallbackState,
        event: StartupFallbackEvent.CandidateFailed,
    ): StartupFallbackReduction {
        if (
            event.sessionGeneration != state.sessionGeneration ||
            event.attemptGeneration != state.activeAttemptGeneration ||
            event.identityKey != state.currentIdentityKey ||
            state.mediaLoaded ||
            state.invalidated ||
            state.pendingConsentIdentityKey != null
        ) {
            return ignored(state)
        }

        val failedIndex = state.candidates.indexOfFirst { it.identityKey == event.identityKey }
        if (failedIndex < 0) return ignored(state)
        val failedCandidate = state.candidates[failedIndex]
        if (failedCandidate.tier == StartupFallbackTier.Unknown) return ignored(state)

        val nextSameTierCandidate = state.candidates
            .asSequence()
            .filter { candidate ->
                candidate.tier == failedCandidate.tier &&
                    candidate.identityKey !in state.attemptedIdentityKeys
            }
            .firstOrNull()
        if (nextSameTierCandidate != null) return attempt(state, nextSameTierCandidate)

        val nextLowerTierCandidate = state.candidates
            .asSequence()
            .firstOrNull { candidate ->
                candidate.tier != StartupFallbackTier.Unknown &&
                    candidate.tier.rank < failedCandidate.tier.rank &&
                    candidate.identityKey !in state.attemptedIdentityKeys &&
                    candidate.identityKey !in state.consentDeniedIdentityKeys
            }
            ?: return ignored(state)
        return StartupFallbackReduction(
            state = state.copy(pendingConsentIdentityKey = nextLowerTierCandidate.identityKey),
            decision = StartupFallbackDecision.RequestConsent(nextLowerTierCandidate),
        )
    }

    private fun reduceConsentGranted(
        state: StartupFallbackState,
        event: StartupFallbackEvent.ConsentGranted,
    ): StartupFallbackReduction {
        if (
            event.sessionGeneration != state.sessionGeneration ||
            event.identityKey != state.pendingConsentIdentityKey ||
            state.mediaLoaded ||
            state.invalidated
        ) {
            return ignored(state)
        }
        val candidate = state.candidates.firstOrNull { it.identityKey == event.identityKey }
            ?: return ignored(state)
        if (candidate.identityKey in state.attemptedIdentityKeys) return ignored(state)
        return attempt(state.copy(pendingConsentIdentityKey = null), candidate)
    }

    private fun reduceConsentDenied(
        state: StartupFallbackState,
        event: StartupFallbackEvent.ConsentDenied,
    ): StartupFallbackReduction {
        if (
            event.sessionGeneration != state.sessionGeneration ||
            event.identityKey != state.pendingConsentIdentityKey
        ) {
            return ignored(state)
        }
        return StartupFallbackReduction(
            state = state.copy(
                pendingConsentIdentityKey = null,
                consentDeniedIdentityKeys = state.consentDeniedIdentityKeys + event.identityKey,
                invalidated = true,
            ),
            decision = StartupFallbackDecision.Ignore,
        )
    }

    private fun attempt(
        state: StartupFallbackState,
        candidate: StartupFallbackCandidate,
    ): StartupFallbackReduction {
        val attemptGeneration = state.activeAttemptGeneration + 1L
        return StartupFallbackReduction(
            state = state.copy(
                currentIdentityKey = candidate.identityKey,
                activeAttemptGeneration = attemptGeneration,
                attemptedIdentityKeys = state.attemptedIdentityKeys + candidate.identityKey,
                pendingConsentIdentityKey = null,
            ),
            decision = StartupFallbackDecision.Attempt(candidate, attemptGeneration),
        )
    }

    private fun ignored(state: StartupFallbackState): StartupFallbackReduction =
        StartupFallbackReduction(state = state, decision = StartupFallbackDecision.Ignore)
}

internal fun StartupFallbackState.reduce(event: StartupFallbackEvent): StartupFallbackReduction =
    StartupFallbackCoordinator.reduce(this, event)

internal val PlayerScreenRuntime.pendingStartupFallbackCandidate: StartupFallbackCandidate?
    get() = startupFallbackState.pendingConsentIdentityKey
        ?.let { identity -> startupFallbackState.candidates.firstOrNull { it.identityKey == identity } }

internal fun PlayerScreenRuntime.markStartupFallbackMediaLoaded() {
    startupFallbackState = startupFallbackState.reduce(StartupFallbackEvent.MediaLoaded).state
}

internal fun PlayerScreenRuntime.invalidateStartupFallbackForManualSourceChange() {
    startupFallbackState = startupFallbackState.reduce(
        StartupFallbackEvent.ManualSourceChanged(startupFallbackState.sessionGeneration),
    ).state
}

internal fun PlayerScreenRuntime.handleStartupFallbackFailure(): Boolean {
    if (!supportsValidatedStartupFallback) return false
    val identityKey = activeSourceIdentityKey ?: return false
    val reduction = startupFallbackState.reduce(
        StartupFallbackEvent.CandidateFailed(
            sessionGeneration = startupFallbackState.sessionGeneration,
            attemptGeneration = startupFallbackState.activeAttemptGeneration,
            identityKey = identityKey,
        ),
    )
    startupFallbackState = reduction.state
    return when (val decision = reduction.decision) {
        is StartupFallbackDecision.Attempt -> {
            errorMessage = null
            switchToSource(decision.candidate.stream, isStartupFallback = true)
            true
        }
        is StartupFallbackDecision.RequestConsent -> {
            errorMessage = null
            controlsVisible = !playerControlsLocked
            true
        }
        StartupFallbackDecision.Ignore -> false
    }
}

internal fun PlayerScreenRuntime.approveStartupFallback() {
    val candidate = pendingStartupFallbackCandidate ?: return
    val reduction = startupFallbackState.reduce(
        StartupFallbackEvent.ConsentGranted(
            sessionGeneration = startupFallbackState.sessionGeneration,
            identityKey = candidate.identityKey,
        ),
    )
    startupFallbackState = reduction.state
    val decision = reduction.decision as? StartupFallbackDecision.Attempt ?: return
    errorMessage = null
    switchToSource(decision.candidate.stream, isStartupFallback = true)
}

internal fun PlayerScreenRuntime.denyStartupFallback() {
    val candidate = pendingStartupFallbackCandidate ?: return
    startupFallbackState = startupFallbackState.reduce(
        StartupFallbackEvent.ConsentDenied(
            sessionGeneration = startupFallbackState.sessionGeneration,
            identityKey = candidate.identityKey,
        ),
    ).state
    controlsVisible = !playerControlsLocked
    errorMessage = null
}

internal fun startupFallbackCandidates(streams: List<StreamItem>): List<StartupFallbackCandidate> {
    if (!supportsValidatedStartupFallback) return emptyList()
    val seen = mutableSetOf<String>()
    return streams.mapNotNull { stream ->
        if (stream.shouldOpenExternally) return@mapNotNull null
        val url = stream.playableDirectUrl?.takeIf(::isDirectHttpPlaybackUrl) ?: return@mapNotNull null
        val tier = startupFallbackTier(stream)
        if (tier == StartupFallbackTier.Unknown) return@mapNotNull null
        val identityKey = stream.playerSourceIdentityKey() ?: "url:$url"
        if (!seen.add(identityKey)) return@mapNotNull null
        StartupFallbackCandidate(
            stream = stream,
            identityKey = identityKey,
            tier = tier,
        )
    }
}

internal fun isValidatedStartupFallbackOs(osName: String): Boolean =
    osName.trim().lowercase().startsWith("windows")

internal fun startupFallbackTier(stream: StreamItem): StartupFallbackTier {
    val parsed = stream.clientResolve?.stream?.raw?.parsed ?: return StartupFallbackTier.Unknown
    val metadata = listOfNotNull(parsed.resolution, parsed.quality)
        .joinToString(" ")
        .trim()
    if (Regex("(?<![A-Za-z0-9])(?:4k|uhd)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(metadata)) {
        return StartupFallbackTier.FourKPlus
    }
    val dimensionHeight = Regex("(?<!\\d)\\d{3,5}\\s*[x×]\\s*(\\d{3,5})(?!\\d)", RegexOption.IGNORE_CASE)
        .find(metadata)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    val labelledMatches = Regex("(?<!\\d)(\\d{3,5})\\s*p(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
        .findAll(metadata)
        .toList()
    if (labelledMatches.size > 1) return StartupFallbackTier.Unknown
    val labelledHeight = labelledMatches
        .firstOrNull()
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    if (dimensionHeight == null && Regex("(?<!\\d)(\\d{3,5})(?!\\d|p)", RegexOption.IGNORE_CASE).containsMatchIn(metadata)) {
        return StartupFallbackTier.Unknown
    }
    val height = dimensionHeight ?: labelledHeight
    if (height == null || height !in 240..8_000) return StartupFallbackTier.Unknown
    return when {
        height >= 2160 -> StartupFallbackTier.FourKPlus
        height >= 1440 -> StartupFallbackTier.P1440
        height >= 1080 -> StartupFallbackTier.P1080
        height >= 720 -> StartupFallbackTier.P720
        else -> StartupFallbackTier.P576OrLower
    }
}

private fun isDirectHttpPlaybackUrl(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
