package com.nuvio.app.features.updater

import com.nuvio.app.features.updater.AppUpdaterRepository.appliesTo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdaterRepositoryTest {
    @Test
    fun releasesAreFilteredByPlatformTagPrefix() {
        val response = """[
            {"tag_name": "desktop-v2.0.0", "assets": [$manifestAsset]},
            {"tag_name": "android-v1.1.0", "assets": [$manifestAsset]},
            {"tag_name": "v3.0.0", "assets": [$manifestAsset]}
        ]"""
        assertEquals("android-v1.1.0", AppUpdaterRepository.selectRelease(response, UpdateChannel.STABLE, "android")?.tagName)
        assertEquals("desktop-v2.0.0", AppUpdaterRepository.selectRelease(response, UpdateChannel.STABLE, "desktop")?.tagName)
    }

    @Test
    fun stableSkipsPrereleasesAndBetaSortsByVersion() {
        val response = """[
            {"tag_name": "desktop-v1.1.0-beta.9", "assets": [$manifestAsset]},
            {"tag_name": "desktop-v1.1.0-beta.10", "assets": [$manifestAsset]},
            {"tag_name": "desktop-v1.0.0", "assets": [$manifestAsset]}
        ]"""
        assertEquals("desktop-v1.0.0", AppUpdaterRepository.selectRelease(response, UpdateChannel.STABLE, "desktop")?.tagName)
        assertEquals("desktop-v1.1.0-beta.10", AppUpdaterRepository.selectRelease(response, UpdateChannel.BETA, "desktop")?.tagName)
    }

    @Test
    fun releasesWithoutManifestAreSkipped() {
        val response = """[
            {"tag_name": "desktop-v1.2.0", "assets": [
                {"name": "KinoSetup-1.2.0.exe", "browser_download_url": "https://example.com/setup.exe"}]},
            {"tag_name": "desktop-v1.1.0", "assets": [$manifestAsset]}
        ]"""
        assertEquals("desktop-v1.1.0", AppUpdaterRepository.selectRelease(response, UpdateChannel.STABLE, "desktop")?.tagName)
    }

    @Test
    fun betaChannelAcceptsStableManifests() {
        val manifest = ReleaseManifest(platform = "desktop", version = "1.1.0")
        assertTrue(manifest.appliesTo("desktop", UpdateChannel.STABLE))
        assertTrue(manifest.appliesTo("desktop", UpdateChannel.BETA))
        assertFalse(manifest.copy(channel = "beta").appliesTo("desktop", UpdateChannel.STABLE))
        assertTrue(manifest.copy(channel = "beta").appliesTo("desktop", UpdateChannel.BETA))
    }

    @Test
    fun resolvedUpdateUsesManifestAssetAndChecksum() {
        val release = GitHubReleaseDto(
            tagName = "desktop-v1.1.0",
            name = "Kino 1.1.0",
            assets = listOf(
                GitHubAssetDto("release-manifest.json", "https://example.com/manifest.json"),
                GitHubAssetDto("KinoSetup-1.1.0.exe", "https://example.com/setup.exe", size = 1024L),
            ),
        )
        val manifest = ReleaseManifest(
            platform = "desktop",
            version = "1.1.0",
            minimumSupportedVersionCode = 50,
            assets = listOf(ReleaseManifestAsset("KinoSetup-1.1.0.exe", 1024L, "abc123")),
        )

        val update = AppUpdaterRepository.resolveUpdate(release, manifest, "desktop", emptyList(), currentVersionCode = 40)

        assertEquals("KinoSetup-1.1.0.exe", update?.assetName)
        assertEquals("abc123", update?.assetSha256)
        assertEquals("1.1.0", update?.version)
        assertEquals(true, update?.mandatory)
    }

    @Test
    fun resolvedUpdateRejectsMismatchedManifestVersion() {
        val release = GitHubReleaseDto(tagName = "desktop-v1.2.0")
        val manifest = ReleaseManifest(platform = "desktop", version = "1.1.0")

        assertFailsWith<IllegalStateException> {
            AppUpdaterRepository.resolveUpdate(release, manifest, "desktop", emptyList(), currentVersionCode = 1)
        }
    }

    @Test
    fun resolvedUpdateIsNullWithoutDeclaredAsset() {
        val release = GitHubReleaseDto(
            tagName = "android-v1.1.0",
            assets = listOf(GitHubAssetDto("other.apk", "https://example.com/other.apk")),
        )
        val manifest = ReleaseManifest(
            platform = "android",
            version = "1.1.0",
            versionCode = 10,
            assets = listOf(ReleaseManifestAsset("Kino-1.1.0.apk", 1L, "sha")),
        )

        assertNull(AppUpdaterRepository.resolveUpdate(release, manifest, "android", listOf("arm64-v8a"), currentVersionCode = 1))
    }

    private val manifestAsset =
        """{"name": "release-manifest.json", "browser_download_url": "https://example.com/release-manifest.json"}"""
}
