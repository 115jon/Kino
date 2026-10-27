package com.nuvio.app.features.updater

internal enum class UpdateDialogPhase {
    Checking,
    Available,
    Downloading,
    ReadyToInstall,
    Failed,
    TestComplete,
}

internal fun AppUpdaterUiState.dialogPhase(): UpdateDialogPhase? = when {
    errorMessage != null -> UpdateDialogPhase.Failed
    isDownloading -> UpdateDialogPhase.Downloading
    isDebugTest && !isUpdateAvailable -> UpdateDialogPhase.TestComplete
    downloadedApkPath != null -> UpdateDialogPhase.ReadyToInstall
    update != null && isUpdateAvailable -> UpdateDialogPhase.Available
    isChecking -> UpdateDialogPhase.Checking
    else -> null
}

internal fun AppUpdaterUiState.visibleDialogPhase(inPlayback: Boolean): UpdateDialogPhase? =
    if (inPlayback || !showDialog) null else dialogPhase()
