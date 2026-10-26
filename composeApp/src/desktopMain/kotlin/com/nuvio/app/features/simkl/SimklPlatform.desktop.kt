package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences
import java.security.MessageDigest
import java.security.SecureRandom

internal actual object SimklPlatformClock {
    actual fun nowEpochMs(): Long = System.currentTimeMillis()
}

internal actual object SimklPkceCrypto {
    private val secureRandom = SecureRandom()

    actual fun secureRandomBytes(size: Int): ByteArray =
        ByteArray(size).also(secureRandom::nextBytes)

    actual fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)
}

internal actual object SimklAuthStorage {
    private const val preferencesName = "nuvio_simkl_auth"
    private const val metadataKey = "simkl_auth_metadata"
    private const val accessTokenKey = "simkl_access_token"
    private const val codeVerifierKey = "simkl_code_verifier"

    actual fun loadMetadataPayload(): String? = load(metadataKey)

    actual fun saveMetadataPayload(payload: String) = save(metadataKey, payload)

    actual fun loadAccessToken(): String? = load(accessTokenKey)

    actual fun saveAccessToken(value: String?) = save(accessTokenKey, value)

    actual fun loadCodeVerifier(): String? = load(codeVerifierKey)

    actual fun saveCodeVerifier(value: String?) = save(codeVerifierKey, value)

    actual fun removeProfile(profileId: Int) {
        listOf(metadataKey, accessTokenKey, codeVerifierKey).forEach { key ->
            DesktopPreferences.remove(preferencesName, ProfileScopedKey.of(key, profileId))
        }
    }

    private fun load(key: String): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(key))

    private fun save(key: String, value: String?) {
        val scopedKey = ProfileScopedKey.of(key)
        if (value.isNullOrBlank()) {
            DesktopPreferences.remove(preferencesName, scopedKey)
        } else {
            DesktopPreferences.putString(preferencesName, scopedKey, value)
        }
    }
}
