package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ReleaseManifestTest {
    @Test
    fun `manifest applies only to its platform and channel`() {
        val manifest = manifest(platform = "android", channel = "stable")

        assertTrue(manifest.appliesTo(platform = "android", channel = "stable"))
        assertFalse(manifest.appliesTo(platform = "desktop", channel = "stable"))
        assertFalse(manifest.appliesTo(platform = "android", channel = "beta"))
        assertFalse(manifest.copy(versionCode = null).appliesTo(platform = "android", channel = "stable"))
    }

    @Test
    fun `android update uses version code`() {
        val manifest = manifest(platform = "android", version = "0.2.1", versionCode = 97)

        assertTrue(manifest.isNewerThan(currentVersion = "9.0.0", currentVersionCode = 96))
        assertFalse(manifest.isNewerThan(currentVersion = "0.1.0", currentVersionCode = 97))
    }

    @Test
    fun `desktop update uses semantic version`() {
        val manifest = manifest(platform = "desktop", version = "0.3.0", versionCode = null)

        assertTrue(manifest.isNewerThan(currentVersion = "0.2.24", currentVersionCode = 96))
        assertFalse(manifest.isNewerThan(currentVersion = "0.3.0", currentVersionCode = 96))
    }

    @Test
    fun `manifest selects only its declared compatible asset`() {
        val manifest = manifest(
            platform = "android",
            assets = listOf(
                ReleaseManifestAsset(name = "Kino-Android-universal.apk", sizeBytes = 10, sha256 = "a"),
            ),
        )
        val candidates = listOf(
            ReleaseAssetCandidate("Kino-Desktop-0.3.0.exe", "desktop-url", 20),
            ReleaseAssetCandidate("Kino-Android-universal.apk", "android-url", 10),
        )

        assertEquals("android-url", manifest.selectAsset(candidates, supportedAbis = emptyList())?.url)
    }

    @Test
    fun `manifest rejects releases without a compatible declared asset`() {
        val manifest = manifest(platform = "android")
        val candidates = listOf(ReleaseAssetCandidate("Kino-Desktop-0.3.0.exe", "desktop-url", 20))

        assertNull(manifest.selectAsset(candidates, supportedAbis = emptyList()))
    }

    @Test
    fun `mandatory update is based on minimum supported version code`() {
        val manifest = manifest(platform = "android", minimumSupportedVersionCode = 96)

        assertTrue(manifest.isMandatoryFor(currentVersionCode = 95))
        assertFalse(manifest.isMandatoryFor(currentVersionCode = 96))
    }

    @Test
    fun `decodes the generated manifest shape`() {
        val manifest = Json.decodeFromString<ReleaseManifest>(
            """
            {
              "schemaVersion": 1,
              "product": "kino",
              "platform": "android",
              "channel": "stable",
              "version": "0.3.0",
              "versionCode": 97,
              "mandatory": false,
              "assets": [{"name":"Kino-Android-0.3.0.apk","sizeBytes":123,"sha256":"abc"}]
            }
            """.trimIndent(),
        )

        assertEquals("0.3.0", manifest.version)
        assertEquals("Kino-Android-0.3.0.apk", manifest.assets.single().name)
    }

    private fun manifest(
        platform: String,
        channel: String = "stable",
        version: String = "0.3.0",
        versionCode: Int? = 97,
        minimumSupportedVersionCode: Int? = null,
        assets: List<ReleaseManifestAsset> = listOf(
            ReleaseManifestAsset(name = "Kino-Android-universal.apk", sizeBytes = 10, sha256 = "a"),
        ),
    ) = ReleaseManifest(
        platform = platform,
        channel = channel,
        version = version,
        versionCode = versionCode,
        minimumSupportedVersionCode = minimumSupportedVersionCode,
        assets = assets,
    )
}
