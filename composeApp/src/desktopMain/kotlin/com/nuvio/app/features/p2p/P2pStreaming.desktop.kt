package com.nuvio.app.features.p2p

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual object P2pSettingsStorage {
    private const val preferencesName = "torrent_settings"
    private const val p2pEnabledKey = "p2p_enabled"
    private const val enableUploadKey = "enable_upload"
    private const val hideTorrentStatsKey = "hide_torrent_stats"
    private const val torrentProfileKey = "torrent_profile"
    private const val cacheSizeKey = "cache_size"

    actual fun loadP2pEnabled(): Boolean? = loadBoolean(p2pEnabledKey)

    actual fun saveP2pEnabled(enabled: Boolean) {
        saveBoolean(p2pEnabledKey, enabled)
    }

    actual fun loadEnableUpload(): Boolean? = loadBoolean(enableUploadKey)

    actual fun saveEnableUpload(enabled: Boolean) {
        saveBoolean(enableUploadKey, enabled)
    }

    actual fun loadHideTorrentStats(): Boolean? = loadBoolean(hideTorrentStatsKey)

    actual fun saveHideTorrentStats(enabled: Boolean) {
        saveBoolean(hideTorrentStatsKey, enabled)
    }

    actual fun loadTorrentProfile(): String? = loadString(torrentProfileKey)

    actual fun saveTorrentProfile(profile: String) {
        saveString(torrentProfileKey, profile)
    }

    actual fun loadCacheSize(): String? = loadString(cacheSizeKey)

    actual fun saveCacheSize(size: String) {
        saveString(cacheSizeKey, size)
    }

    private fun loadString(key: String): String? =
        DesktopPreferences.getString(preferencesName, ProfileScopedKey.of(key))

    private fun saveString(key: String, value: String) {
        DesktopPreferences.putString(preferencesName, ProfileScopedKey.of(key), value)
    }

    private fun loadBoolean(key: String): Boolean? =
        DesktopPreferences.getBoolean(preferencesName, ProfileScopedKey.of(key))

    private fun saveBoolean(key: String, value: Boolean) {
        DesktopPreferences.putBoolean(preferencesName, ProfileScopedKey.of(key), value)
    }
}

actual object P2pStreamingEngine {
    private val _state = MutableStateFlow<P2pStreamingState>(P2pStreamingState.Idle)
    actual val state: StateFlow<P2pStreamingState> = _state.asStateFlow()
    private val _cacheState = MutableStateFlow(P2pCacheUiState())
    actual val cacheState: StateFlow<P2pCacheUiState> = _cacheState.asStateFlow()

    actual suspend fun startStream(request: P2pStreamRequest): String {
        val message = "P2P streaming is not available on this platform"
        _state.value = P2pStreamingState.Error(message)
        throw P2pStreamingException(message)
    }

    actual suspend fun clearCache(): P2pCacheClearResult =
        P2pCacheClearResult(reclaimedBytes = 0L, remainingBytes = 0L, protectedBytes = 0L)

    actual fun stopStream() {
        _state.value = P2pStreamingState.Idle
    }

    actual fun shutdown() {
        _state.value = P2pStreamingState.Idle
    }
}
