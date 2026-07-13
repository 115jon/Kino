package com.nuvio.app.core.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.storage.LocalAccountDataCleaner
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object AuthRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("AuthRepository")

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var initialized = false
    private var validatedRemoteUserId: String? = null

    fun initialize() {
        if (initialized) return
        initialized = true

        val savedAnonId = AuthStorage.loadAnonymousUserId()
        if (savedAnonId != null) {
            _state.value = AuthState.Authenticated(
                userId = savedAnonId,
                email = null,
                isAnonymous = true,
            )
        }

        scope.launch {
            SupabaseProvider.client.auth.sessionStatus.collect { status ->
                if (AuthStorage.loadAnonymousUserId() != null) return@collect
                when (status) {
                    is SessionStatus.Authenticated -> {
                        val user = status.session.user
                        val userId = user?.id.orEmpty()
                        if (!validateRemoteSession(userId)) return@collect
                        _state.value = AuthState.Authenticated(
                            userId = userId,
                            email = user?.email,
                            isAnonymous = false,
                        )
                    }
                    is SessionStatus.NotAuthenticated -> {
                        _state.value = AuthState.Unauthenticated
                    }
                    is SessionStatus.Initializing -> {
                        if (AuthStorage.loadAnonymousUserId() == null) {
                            _state.value = AuthState.Loading
                        }
                    }
                    is SessionStatus.RefreshFailure -> {
                        _state.value = AuthState.Unauthenticated
                    }
                }
            }
        }
    }

    private suspend fun validateRemoteSession(userId: String): Boolean {
        if (userId.isBlank() || validatedRemoteUserId == userId) return true

        return runCatching {
            SupabaseProvider.client.auth.retrieveUserForCurrentSession(false)
            validatedRemoteUserId = userId
            true
        }.getOrElse { e ->
            if (isInvalidRemoteSessionError(e)) {
                log.w(e) { "Stored Supabase session no longer belongs to an active account; clearing local auth" }
                clearLocalSessionAfterRemoteInvalidation()
                false
            } else {
                log.w(e) { "Unable to validate stored Supabase session; keeping cached auth state" }
                true
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    fun signInAnonymously() {
        _error.value = null
        val userId = Uuid.random().toString()
        AuthStorage.saveAnonymousUserId(userId)
        _state.value = AuthState.Authenticated(
            userId = userId,
            email = null,
            isAnonymous = true,
        )
    }

    private fun getCleanErrorMessage(e: Throwable, defaultMessage: String): String {
        val message = e.message ?: return defaultMessage
        return sanitizeAuthErrorMessage(message, defaultMessage)
    }

    private fun buildNormalizedAuthEmail(email: String): String = normalizeAuthEmail(email)
    private fun buildEmailDiagnostics(email: String): AuthEmailDiagnostics = buildAuthEmailDiagnostics(email)

    suspend fun signUpWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        val emailDiagnostics = buildEmailDiagnostics(email)
        val normalizedEmail = buildNormalizedAuthEmail(email)
        log.i { "Email sign-up attempt ${emailDiagnostics.toLogFields()}" }
        SupabaseProvider.client.auth.signUpWith(Email) {
            this.email = normalizedEmail
            this.password = password
        }
        Unit
    }.onFailure { e ->
        log.e(e) { "Email sign-up failed ${buildEmailDiagnostics(email).toLogFields()}" }
        _error.value = getCleanErrorMessage(e, getString(Res.string.auth_sign_up_failed))
    }

    suspend fun signInWithEmail(email: String, password: String): Result<Unit> = runCatching {
        _error.value = null
        val emailDiagnostics = buildEmailDiagnostics(email)
        val normalizedEmail = buildNormalizedAuthEmail(email)
        log.i { "Email sign-in attempt ${emailDiagnostics.toLogFields()}" }
        SupabaseProvider.client.auth.signInWith(Email) {
            this.email = normalizedEmail
            this.password = password
        }
    }.onFailure { e ->
        log.e(e) { "Email sign-in failed ${buildEmailDiagnostics(email).toLogFields()}" }
        _error.value = getCleanErrorMessage(e, getString(Res.string.auth_sign_in_failed))
    }

    suspend fun signOut(): Result<Unit> {
        _error.value = null
        val anonymousRead = runCatching { AuthStorage.loadAnonymousUserId() }
        val wasAnonymous = anonymousRead.getOrNull() != null
        val anonymousClear = runCatching { AuthStorage.clearAnonymousUserId() }
        validatedRemoteUserId = null
        val remoteSignOut = if (wasAnonymous) {
            Result.success(Unit)
        } else {
            runCatching { SupabaseProvider.client.auth.signOut() }
        }

        val fallbackSessionClear = if (remoteSignOut.isFailure) {
            runCatching { SupabaseProvider.client.auth.clearSession() }
                .onFailure { error -> log.w(error) { "Failed to clear Supabase session after sign-out failure" } }
        } else {
            Result.success(Unit)
        }
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe() }
        _state.value = AuthState.Unauthenticated

        val failure = anonymousRead.exceptionOrNull()
            ?: anonymousClear.exceptionOrNull()
            ?: remoteSignOut.exceptionOrNull()
            ?: fallbackSessionClear.exceptionOrNull()
            ?: localCleanup.exceptionOrNull()
        val cancellation = remoteSignOut.exceptionOrNull() as? CancellationException
            ?: fallbackSessionClear.exceptionOrNull() as? CancellationException
        if (cancellation != null) throw cancellation
        return if (failure == null) {
            Result.success(Unit)
        } else {
            log.e(failure) { "Sign-out did not complete cleanly; all local cleanup steps were attempted" }
            _error.value = failure.message ?: runCatching {
                getString(Res.string.auth_sign_out_failed)
            }.getOrDefault("Sign out failed")
            Result.failure(failure)
        }
    }

    suspend fun signOutIfSessionInvalid(error: Throwable, source: String): Boolean {
        if (!isInvalidRemoteSessionError(error)) return false

        log.w(error) { "$source failed because the current Supabase account/session is no longer valid; clearing local auth" }
        clearLocalSessionAfterRemoteInvalidation()
        return true
    }

    private suspend fun clearLocalSessionAfterRemoteInvalidation() {
        _error.value = null
        AuthStorage.clearAnonymousUserId()
        validatedRemoteUserId = null
        runCatching {
            SupabaseProvider.client.auth.clearSession()
        }.onFailure { e ->
            log.w(e) { "Failed to clear Supabase session after remote invalidation; continuing local reset" }
        }
        val localCleanup = runCatching { LocalAccountDataCleaner.wipe() }
        _state.value = AuthState.Unauthenticated
        localCleanup.onFailure { error ->
            log.e(error) { "Local account cleanup failed after remote session invalidation" }
        }
    }

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        _error.value = null
        SupabaseProvider.client.functions.invoke("delete-account")
        SupabaseProvider.client.auth.signOut()
        validatedRemoteUserId = null
        try {
            LocalAccountDataCleaner.wipe()
        } finally {
            _state.value = AuthState.Unauthenticated
        }
    }.onFailure { e ->
        log.e(e) { "Account deletion failed" }
        _error.value = e.message ?: getString(Res.string.auth_account_deletion_failed)
    }

    fun clearError() {
        _error.value = null
    }

    private fun isInvalidRemoteSessionError(error: Throwable): Boolean {
        val restError = error.findCause<RestException>()
        if (restError?.statusCode == 401 || restError?.statusCode == 403) return true

        val message = buildString {
            append(error.message.orEmpty())
            if (restError != null) {
                append(' ')
                append(restError.error)
                append(' ')
                append(restError.description)
            }
        }.lowercase()

        return (
            "jwt" in message &&
                ("invalid" in message || "expired" in message || "malformed" in message)
            ) || (
            "user" in message &&
                ("does not exist" in message || "not found" in message || "deleted" in message)
            ) || (
            "foreign key" in message &&
                ("auth.users" in message || "user_id" in message)
            )
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }
}

internal fun sanitizeAuthErrorMessage(message: String?, defaultMessage: String): String {
    val trimmed = message?.trim().orEmpty()
    if (trimmed.isBlank()) return defaultMessage
    if (trimmed.contains("exceed_egress_quota", ignoreCase = true) ||
        trimmed.contains("project is restricted", ignoreCase = true)
    ) {
        return "The service is temporarily unavailable. Please try again later."
    }

    if (trimmed.contains("URL:", ignoreCase = true) ||
        trimmed.contains("Headers:", ignoreCase = true) ||
        trimmed.contains("Http Method:", ignoreCase = true) ||
        trimmed.equals("Unknown Error", ignoreCase = true)
    ) {
        return defaultMessage
    }

    val jsonStart = trimmed.indexOf('{')
    val jsonEnd = trimmed.lastIndexOf('}')
    if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
        val jsonStr = trimmed.substring(jsonStart, jsonEnd + 1)
        try {
            val element = Json.parseToJsonElement(jsonStr)
            val obj = element.jsonObject
            return obj["error_description"]?.jsonPrimitive?.content
                ?: obj["error"]?.jsonPrimitive?.content
                ?: obj["msg"]?.jsonPrimitive?.content
                ?: obj["message"]?.jsonPrimitive?.content
                ?: trimmed
        } catch (_: Exception) {
            // fall back
        }
    }
    if (trimmed.contains("errorDescription=")) {
        val description = trimmed.substringAfter("errorDescription=").substringBefore(")").trim()
        if (description.isNotEmpty() && !description.startsWith("null")) return description
    }
    if (trimmed.contains("error_description=")) {
        val description = trimmed.substringAfter("error_description=").substringBefore(")").trim()
        if (description.isNotEmpty() && !description.startsWith("null")) return description
    }
    return trimmed
}

internal fun normalizeAuthEmail(email: String): String = email.trim()

internal data class AuthEmailDiagnostics(
    val originalLength: Int,
    val normalizedLength: Int,
    val normalizationChanged: Boolean,
    val hadLeadingWhitespace: Boolean,
    val hadTrailingWhitespace: Boolean,
    val hasInternalWhitespace: Boolean,
    val hasControlCharacters: Boolean,
) {
    fun toLogFields(): String =
        "emailMetrics={rawLen=$originalLength, normalizedLen=$normalizedLength, changed=$normalizationChanged, leadingWs=$hadLeadingWhitespace, trailingWs=$hadTrailingWhitespace, internalWs=$hasInternalWhitespace, controlChars=$hasControlCharacters}"
}

internal fun buildAuthEmailDiagnostics(email: String): AuthEmailDiagnostics {
    val normalizedEmail = normalizeAuthEmail(email)
    val withoutLeadingWhitespace = email.trimStart()
    val withoutTrailingWhitespace = email.trimEnd()
    return AuthEmailDiagnostics(
        originalLength = email.length,
        normalizedLength = normalizedEmail.length,
        normalizationChanged = normalizedEmail != email,
        hadLeadingWhitespace = withoutLeadingWhitespace != email,
        hadTrailingWhitespace = withoutTrailingWhitespace != email,
        hasInternalWhitespace = normalizedEmail.any(Char::isWhitespace),
        hasControlCharacters = email.any { it.code < 32 || it.code == 127 },
    )
}
