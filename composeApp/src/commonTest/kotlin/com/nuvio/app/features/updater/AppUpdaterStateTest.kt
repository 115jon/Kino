package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AppUpdaterStateTest {
    @Test
    fun `dismissing the dialog clears visible prompt state`() {
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
    fun `failed download keeps the dialog open for retry`() {
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

    @Test
    fun `dialog follows the update lifecycle`() {
        val available = AppUpdaterUiState(update = update(), isUpdateAvailable = true, showDialog = true)

        assertEquals(UpdateDialogPhase.Available, available.dialogPhase())
        assertEquals(UpdateDialogPhase.Downloading, available.copy(isDownloading = true).dialogPhase())
        assertEquals(UpdateDialogPhase.ReadyToInstall, available.copy(downloadedApkPath = "setup.exe").dialogPhase())
        assertEquals(UpdateDialogPhase.Failed, available.copy(errorMessage = "failed").dialogPhase())
        assertNull(AppUpdaterUiState().dialogPhase())
    }

    @Test
    fun `dialog is deferred during playback and after later`() {
        val available = AppUpdaterUiState(update = update(), isUpdateAvailable = true, showDialog = true)

        assertNull(available.visibleDialogPhase(inPlayback = true))
        assertEquals(UpdateDialogPhase.Available, available.visibleDialogPhase(inPlayback = false))
        assertNull(available.dismissed().visibleDialogPhase(inPlayback = false))
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
