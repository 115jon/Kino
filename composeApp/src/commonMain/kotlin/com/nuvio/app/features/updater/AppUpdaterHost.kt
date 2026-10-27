package com.nuvio.app.features.updater

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_continue
import nuvio.composeapp.generated.resources.action_later
import nuvio.composeapp.generated.resources.updates_message_allow_installs
import nuvio.composeapp.generated.resources.updates_title_allow_installs
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppUpdaterHost(
    controller: AppUpdaterController,
    inPlayback: Boolean,
    content: @Composable () -> Unit,
) {
    content()
    if (!AppFeaturePolicy.inAppUpdaterEnabled || !AppUpdaterPlatform.isSupported) return

    val state by controller.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(controller) {
        controller.ensureAutoCheckStarted()
    }

    val phase = state.visibleDialogPhase(inPlayback)
    if (phase != null) {
        AppUpdateDialog(
            state = state,
            phase = phase,
            onDownload = controller::downloadUpdate,
            onInstall = controller::installDownloadedUpdate,
            onRetry = {
                if (state.update != null) {
                    controller.downloadUpdate()
                } else {
                    controller.checkForUpdates(force = true, showNoUpdateFeedback = true)
                }
            },
            onDismiss = controller::dismissDialog,
        )
    }

    if (!inPlayback && state.showUnknownSourcesDialog) {
        UnknownSourcesDialog(
            onContinue = controller::resumeInstallation,
            onDismiss = controller::dismissDialog,
        )
    }
}

@Composable
private fun UnknownSourcesDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.updates_title_allow_installs),
    ) {
        Text(
            text = stringResource(Res.string.updates_message_allow_installs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textSecondary,
        )
        DialogButtons {
            DialogButton(
                text = stringResource(Res.string.action_later),
                onClick = onDismiss,
            )
            DialogButton(
                text = stringResource(Res.string.action_continue),
                onClick = onContinue,
                style = DialogButtonStyle.Primary,
            )
        }
    }
}
