package com.nuvio.app.features.updater

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

private const val gitHubOwner = "115jon"
private const val gitHubRepo = "Kino"
private const val gitHubApiBase = "https://api.github.com"
private const val releaseChannel = "stable"
private const val releaseManifestName = "release-manifest.json"

data class AppUpdate(
    val tag: String,
    val version: String,
    val versionCode: Int?,
    val mandatory: Boolean,
    val title: String,
    val notes: String,
    val releaseUrl: String?,
    val assetName: String,
    val assetUrl: String,
    val assetSha256: String,
    val assetSizeBytes: Long?,
)

data class AppUpdaterUiState(
    val isChecking: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateAvailable: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApkPath: String? = null,
    val showDialog: Boolean = false,
    val showUnknownSourcesDialog: Boolean = false,
    val errorMessage: String? = null,
)

internal fun AppUpdaterUiState.dismissed(): AppUpdaterUiState = copy(
    showDialog = false,
    showUnknownSourcesDialog = false,
    errorMessage = null,
)

internal fun AppUpdaterUiState.downloadFailed(message: String): AppUpdaterUiState = copy(
    isDownloading = false,
    downloadProgress = null,
    downloadedApkPath = null,
    errorMessage = message,
    showDialog = true,
)

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
private data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
)

private val appUpdaterJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private class NoChannelReleaseException : IllegalStateException(
    runBlocking { getString(Res.string.updates_no_channel_release) },
)

private object AppUpdaterRepository {
    suspend fun getLatestChannelUpdate(): Result<AppUpdate> = runCatching {
        val response = httpRequestRaw(
            method = "GET",
            url = "$gitHubApiBase/repos/$gitHubOwner/$gitHubRepo/releases?per_page=100",
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "User-Agent" to "Kino",
            ),
            body = "",
        )
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }

        val releases = appUpdaterJson.decodeFromString<List<GitHubReleaseDto>>(response.body)
        val release = releases.firstOrNull { it.matchesRequestedPlatform() && !it.draft && !it.prerelease }
            ?: throw NoChannelReleaseException()

        val manifestAsset = release.assets.firstOrNull { it.name == releaseManifestName }
            ?: error(getString(Res.string.updates_release_missing_manifest))
        val manifestResponse = httpRequestRaw(
            method = "GET",
            url = manifestAsset.browserDownloadUrl,
            headers = mapOf(
                "Accept" to "application/json",
                "User-Agent" to "Kino",
            ),
            body = "",
        )
        if (manifestResponse.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, manifestResponse.status))
        }

        val manifest = appUpdaterJson.decodeFromString<ReleaseManifest>(manifestResponse.body)
        if (!manifest.appliesTo(AppUpdaterPlatform.platform, releaseChannel)) {
            throw NoChannelReleaseException()
        }

        val tag = release.tagName?.takeIf { it.isNotBlank() }
            ?: release.name?.takeIf { it.isNotBlank() }
            ?: error(getString(Res.string.updates_release_missing_title))
        val expectedVersion = tag.removePrefix("${AppUpdaterPlatform.platform}-v")
        check(manifest.version == expectedVersion) { "Release manifest version does not match its tag." }

        val asset = manifest.selectAsset(
            candidates = release.assets
                .filterNot { it.name == releaseManifestName }
                .map { candidate ->
                    ReleaseAssetCandidate(
                        name = candidate.name,
                        url = candidate.browserDownloadUrl,
                        sizeBytes = candidate.size,
                    )
                },
            supportedAbis = AppUpdaterPlatform.getSupportedAbis(),
        )
            ?: error(getString(Res.string.updates_apk_asset_missing))

        AppUpdate(
            tag = tag,
            version = manifest.version,
            versionCode = manifest.versionCode,
            mandatory = manifest.mandatory || manifest.isMandatoryFor(AppVersionConfig.VERSION_CODE),
            title = release.name?.takeIf { it.isNotBlank() } ?: tag,
            notes = release.body.orEmpty(),
            releaseUrl = release.htmlUrl,
            assetName = asset.name,
            assetUrl = asset.url,
            assetSha256 = manifest.assets.first { it.name == asset.name }.sha256,
            assetSizeBytes = asset.sizeBytes,
        )
    }

    private fun GitHubReleaseDto.matchesRequestedPlatform(): Boolean {
        val prefix = "${AppUpdaterPlatform.platform}-v"
        return tagName?.startsWith(prefix, ignoreCase = true) == true
    }
}

class AppUpdaterController internal constructor(
    private val scope: CoroutineScope,
) {
    private val _uiState = MutableStateFlow(AppUpdaterUiState())
    val uiState: StateFlow<AppUpdaterUiState> = _uiState.asStateFlow()

    private var autoCheckStarted = false

    fun ensureAutoCheckStarted() {
        if (autoCheckStarted || !AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            return
        }
        autoCheckStarted = true
        checkForUpdates(force = false, showNoUpdateFeedback = false)
    }

    fun checkForUpdates(force: Boolean, showNoUpdateFeedback: Boolean) {
        if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) {
            if (showNoUpdateFeedback) {
                scope.launch {
                    NuvioToastController.show(getString(Res.string.updates_not_available))
                }
            }
            return
        }

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isChecking = true,
                    errorMessage = null,
                    showUnknownSourcesDialog = false,
                )
            }

            val ignoredTag = AppUpdaterPlatform.getIgnoredTag()
            val result = AppUpdaterRepository.getLatestChannelUpdate()

            result.onSuccess { update ->
                val remoteNewer = update.isNewerThanCurrent()
                val ignored = ignoredTag != null && ignoredTag == update.tag
                val shouldShowDialog = force || update.mandatory || (remoteNewer && !ignored)

                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        update = update.takeIf { remoteNewer },
                        isUpdateAvailable = remoteNewer,
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = state.downloadedApkPath.takeIf { remoteNewer },
                        showDialog = shouldShowDialog,
                        showUnknownSourcesDialog = false,
                        errorMessage = null,
                    )
                }

                if (showNoUpdateFeedback && !remoteNewer) {
                    NuvioToastController.show(getString(Res.string.updates_latest_version))
                }
            }.onFailure { error ->
                _uiState.update { state ->
                    state.copy(
                        isChecking = false,
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = null,
                        update = null,
                        isUpdateAvailable = false,
                        showDialog = force && error !is NoChannelReleaseException,
                        showUnknownSourcesDialog = false,
                        errorMessage = if (force && error !is NoChannelReleaseException) {
                            error.message ?: getString(Res.string.updates_check_failed)
                        } else {
                            null
                        },
                    )
                }

                if (showNoUpdateFeedback || error is NoChannelReleaseException) {
                    NuvioToastController.show(error.message ?: getString(Res.string.updates_check_failed))
                }
            }
        }
    }

    private fun AppUpdate.isNewerThanCurrent(): Boolean =
        ReleaseManifest(
            platform = AppUpdaterPlatform.platform,
            channel = releaseChannel,
            version = version,
            versionCode = versionCode,
        ).isNewerThan(
            currentVersion = AppVersionConfig.VERSION_NAME,
            currentVersionCode = AppVersionConfig.VERSION_CODE,
        )

    fun dismissDialog() {
        _uiState.update(AppUpdaterUiState::dismissed)
    }

    fun ignoreThisVersion() {
        val tag = _uiState.value.update?.tag ?: return
        AppUpdaterPlatform.setIgnoredTag(tag)
        dismissDialog()
    }

    fun downloadUpdate() {
        val update = _uiState.value.update ?: return

        scope.launch {
            _uiState.update { state ->
                state.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    errorMessage = null,
                )
            }

            AppUpdaterPlatform.downloadApk(
                assetUrl = update.assetUrl,
                assetName = update.assetName,
                expectedSha256 = update.assetSha256,
            ) { downloadedBytes, totalBytes ->
                val progress = if (totalBytes != null && totalBytes > 0L) {
                    (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                } else {
                    null
                }
                _uiState.update { state -> state.copy(downloadProgress = progress) }
            }.onSuccess { path ->
                _uiState.update { state ->
                    state.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        downloadedApkPath = path,
                        errorMessage = null,
                    )
                }
                installDownloadedUpdate()
            }.onFailure { error ->
                _uiState.update {
                    it.downloadFailed(error.message ?: getString(Res.string.updates_download_failed))
                }
            }
        }
    }

    fun installDownloadedUpdate() {
        val apkPath = _uiState.value.downloadedApkPath ?: return
        if (!AppUpdaterPlatform.canRequestPackageInstalls()) {
            _uiState.update { state -> state.copy(showUnknownSourcesDialog = true, showDialog = true) }
            return
        }

        AppUpdaterPlatform.installDownloadedApk(apkPath).onSuccess {
            _uiState.update { state -> state.copy(showUnknownSourcesDialog = false) }
        }.onFailure { error ->
            scope.launch {
                val fallbackMessage = error.message ?: getString(Res.string.updates_install_failed)
                _uiState.update { state ->
                    state.copy(
                        errorMessage = fallbackMessage,
                        showDialog = true,
                    )
                }
            }
        }
    }

    fun resumeInstallation() {
        if (AppUpdaterPlatform.canRequestPackageInstalls()) {
            installDownloadedUpdate()
        } else {
            AppUpdaterPlatform.openUnknownSourcesSettings()
        }
    }
}

@Composable
fun rememberAppUpdaterController(): AppUpdaterController {
    val scope = rememberCoroutineScope()
    return remember(scope) { AppUpdaterController(scope) }
}

internal fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val units = listOf("B", "KB", "MB", "GB")
    var value = sizeBytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val roundedValue = if (value >= 10 || unitIndex == 0) {
        value.toInt().toString()
    } else {
        ((value * 10).toInt() / 10.0).toString()
    }
    return "$roundedValue ${localizedByteUnit(units[unitIndex])}"
}
