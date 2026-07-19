package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AppUpdaterStateTest {
    @Test
    fun `dismissing banner clears visible prompt state`() {
        val state = AppUpdaterUiState(
            update = update(),
            showDialog = true,
            showUnknownSourcesDialog = true,
            errorMessage = "failed",
        )

        val dismissed = state.dismissed()

        assertFalse(dismissed.showDialog)
        assertFalse(dismissed.showUnknownSourcesDialog)
        assertNull(dismissed.errorMessage)
        assertEquals(state.update, dismissed.update)
    }

    @Test
    fun `failed download keeps banner open for retry`() {
        val failed = AppUpdaterUiState(
            update = update(),
            isDownloading = true,
            downloadProgress = 0.4f,
        ).downloadFailed("checksum mismatch")

        assertFalse(failed.isDownloading)
        assertNull(failed.downloadProgress)
        assertNull(failed.downloadedApkPath)
        assertEquals("checksum mismatch", failed.errorMessage)
        assertEquals(true, failed.showDialog)
    }

    private fun update() = AppUpdate(
        tag = "desktop-v1.2.0",
        version = "1.2.0",
        versionCode = null,
        mandatory = false,
        title = "Kino 1.2.0",
        notes = "Notes",
        releaseUrl = null,
        assetName = "Kino-1.2.0.exe",
        assetUrl = "https://example.com/update.exe",
        assetSha256 = "sha256",
        assetSizeBytes = 1024L,
    )
}
