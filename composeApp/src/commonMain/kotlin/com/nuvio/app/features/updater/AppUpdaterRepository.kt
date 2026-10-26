package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_apk_asset_missing
import nuvio.composeapp.generated.resources.updates_github_api_error
import nuvio.composeapp.generated.resources.updates_release_missing_manifest
import org.jetbrains.compose.resources.getString

@Serializable
internal data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAssetDto> = emptyList(),
)

@Serializable
internal data class GitHubAssetDto(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long? = null,
    @SerialName("content_type") val contentType: String? = null,
)

internal class NoChannelReleaseException : IllegalStateException()

internal object AppUpdaterRepository {
    private const val releasesUrl = "https://api.github.com/repos/115jon/Kino/releases?per_page=100"
    private const val releaseManifestName = "release-manifest.json"
    private const val userAgent = "Kino"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun getLatestChannelUpdate(channel: UpdateChannel): Result<AppUpdate> = runCatching {
        val platform = AppUpdaterPlatform.platform
        val release = selectRelease(
            responseBody = fetch(releasesUrl, accept = "application/vnd.github+json"),
            channel = channel,
            platform = platform,
        ) ?: throw NoChannelReleaseException()
        val manifestAsset = release.assets.firstOrNull { it.name == releaseManifestName }
            ?: error(getString(Res.string.updates_release_missing_manifest))
        val manifest = json.decodeFromString<ReleaseManifest>(
            fetch(manifestAsset.browserDownloadUrl, accept = "application/json"),
        )
        if (!manifest.appliesTo(platform, channel)) throw NoChannelReleaseException()
        resolveUpdate(
            release = release,
            manifest = manifest,
            platform = platform,
            supportedAbis = AppUpdaterPlatform.getSupportedAbis(),
            currentVersionCode = AppVersionConfig.VERSION_CODE,
        ) ?: error(getString(Res.string.updates_apk_asset_missing))
    }

    private suspend fun fetch(url: String, accept: String): String {
        val response = httpRequestRaw(
            method = "GET",
            url = url,
            headers = mapOf(
                "Accept" to accept,
                "User-Agent" to userAgent,
            ),
            body = "",
        )
        currentCoroutineContext().ensureActive()
        if (response.status == 404) throw NoChannelReleaseException()
        if (response.status !in 200..299) {
            error(getString(Res.string.updates_github_api_error, response.status))
        }
        return response.body
    }

    internal fun tagPrefix(platform: String): String = "$platform-v"

    internal fun selectRelease(
        responseBody: String,
        channel: UpdateChannel,
        platform: String,
    ): GitHubReleaseDto? = ReleaseSelector
        .eligibleReleases(
            releases = json.decodeFromString<List<GitHubReleaseDto>>(responseBody),
            channel = channel,
            tagPrefix = tagPrefix(platform),
        )
        .firstOrNull { release -> release.assets.any { it.name == releaseManifestName } }

    internal fun resolveUpdate(
        release: GitHubReleaseDto,
        manifest: ReleaseManifest,
        platform: String,
        supportedAbis: List<String>,
        currentVersionCode: Int,
    ): AppUpdate? {
        val tag = release.tagName?.takeIf { it.isNotBlank() } ?: return null
        check(manifest.version == tag.removePrefix(tagPrefix(platform))) {
            "Release manifest version does not match its tag."
        }
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
            supportedAbis = supportedAbis,
        ) ?: return null
        return AppUpdate(
            tag = tag,
            version = manifest.version,
            versionCode = manifest.versionCode,
            mandatory = manifest.mandatory || manifest.isMandatoryFor(currentVersionCode),
            title = release.name?.takeIf { it.isNotBlank() } ?: tag,
            notes = release.body.orEmpty(),
            releaseUrl = release.htmlUrl,
            assetName = asset.name,
            assetUrl = asset.url,
            assetSha256 = manifest.assets.first { it.name == asset.name }.sha256,
            assetSizeBytes = asset.sizeBytes,
        )
    }

    internal fun ReleaseManifest.appliesTo(platform: String, channel: UpdateChannel): Boolean =
        acceptedManifestChannels(channel).any { appliesTo(platform, it) }

    private fun acceptedManifestChannels(channel: UpdateChannel): List<String> = when (channel) {
        UpdateChannel.STABLE -> listOf(UpdateChannel.STABLE.storedValue)
        UpdateChannel.BETA -> listOf(UpdateChannel.STABLE.storedValue, UpdateChannel.BETA.storedValue)
    }
}
