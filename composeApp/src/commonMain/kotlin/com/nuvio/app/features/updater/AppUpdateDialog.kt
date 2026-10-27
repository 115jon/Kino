package com.nuvio.app.features.updater

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.build.AppVersionConfig
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_close
import nuvio.composeapp.generated.resources.action_install
import nuvio.composeapp.generated.resources.action_later
import nuvio.composeapp.generated.resources.action_retry
import nuvio.composeapp.generated.resources.action_update
import nuvio.composeapp.generated.resources.updates_action_hide
import nuvio.composeapp.generated.resources.updates_action_restart
import nuvio.composeapp.generated.resources.updates_asset_line
import nuvio.composeapp.generated.resources.updates_debug_test_complete
import nuvio.composeapp.generated.resources.updates_downloading_progress
import nuvio.composeapp.generated.resources.updates_message_available
import nuvio.composeapp.generated.resources.updates_message_ready
import nuvio.composeapp.generated.resources.updates_message_restart
import nuvio.composeapp.generated.resources.updates_preparing_download
import nuvio.composeapp.generated.resources.updates_release_notes
import nuvio.composeapp.generated.resources.updates_title_available
import nuvio.composeapp.generated.resources.updates_title_failed
import nuvio.composeapp.generated.resources.updates_title_ready
import nuvio.composeapp.generated.resources.updates_title_status
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AppUpdateDialog(
    state: AppUpdaterUiState,
    phase: UpdateDialogPhase,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val update = state.update
    val restartsApp = AppUpdaterPlatform.platform == "desktop"
    val availableMessage = update?.let {
        stringResource(Res.string.updates_message_available, it.version, AppVersionConfig.VERSION_NAME)
    }
    DialogSurface(
        onDismissRequest = onDismiss,
        title = stringResource(phase.titleResource()),
        message = availableMessage.takeIf {
            phase == UpdateDialogPhase.Available || phase == UpdateDialogPhase.Downloading
        },
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        UpdateDialogBody(state, phase, update, restartsApp)
        DialogButtons {
            UpdateDialogActions(phase, restartsApp, onDownload, onInstall, onRetry, onDismiss)
        }
    }
}

@Composable
private fun UpdateDialogBody(
    state: AppUpdaterUiState,
    phase: UpdateDialogPhase,
    update: AppUpdate?,
    restartsApp: Boolean,
) {
    val tokens = MaterialTheme.nuvio
    when (phase) {
        UpdateDialogPhase.Available -> update?.notes?.takeIf(String::isNotBlank)?.let { ReleaseNotes(it) }
        UpdateDialogPhase.Downloading -> DownloadProgress(state, update)
        UpdateDialogPhase.ReadyToInstall -> {
            BodyText(stringResource(Res.string.updates_message_ready))
            if (restartsApp) BodyText(stringResource(Res.string.updates_message_restart))
        }
        UpdateDialogPhase.Failed -> BodyText(state.errorMessage.orEmpty(), tokens.colors.danger)
        UpdateDialogPhase.Checking -> NuvioLoadingIndicator(
            modifier = Modifier.size(tokens.icons.md),
            color = tokens.colors.accent,
        )
        UpdateDialogPhase.TestComplete -> BodyText(stringResource(Res.string.updates_debug_test_complete))
    }
}

@Composable
private fun UpdateDialogActions(
    phase: UpdateDialogPhase,
    restartsApp: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (phase) {
        UpdateDialogPhase.Available -> {
            DialogButton(text = stringResource(Res.string.action_later), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.action_update),
                onClick = onDownload,
                style = DialogButtonStyle.Primary,
            )
        }
        UpdateDialogPhase.Downloading -> {
            DialogButton(text = stringResource(Res.string.updates_action_hide), onClick = onDismiss)
        }
        UpdateDialogPhase.ReadyToInstall -> {
            DialogButton(text = stringResource(Res.string.action_later), onClick = onDismiss)
            DialogButton(
                text = stringResource(if (restartsApp) Res.string.updates_action_restart else Res.string.action_install),
                onClick = onInstall,
                style = DialogButtonStyle.Primary,
            )
        }
        UpdateDialogPhase.Failed -> {
            DialogButton(text = stringResource(Res.string.action_close), onClick = onDismiss)
            DialogButton(
                text = stringResource(Res.string.action_retry),
                onClick = onRetry,
                style = DialogButtonStyle.Primary,
            )
        }
        UpdateDialogPhase.Checking, UpdateDialogPhase.TestComplete -> {
            DialogButton(text = stringResource(Res.string.action_close), onClick = onDismiss)
        }
    }
}

private fun UpdateDialogPhase.titleResource(): StringResource = when (this) {
    UpdateDialogPhase.Available, UpdateDialogPhase.Downloading -> Res.string.updates_title_available
    UpdateDialogPhase.ReadyToInstall -> Res.string.updates_title_ready
    UpdateDialogPhase.Failed -> Res.string.updates_title_failed
    UpdateDialogPhase.Checking, UpdateDialogPhase.TestComplete -> Res.string.updates_title_status
}

@Composable
private fun ReleaseNotes(notes: String) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        Text(
            text = stringResource(Res.string.updates_release_notes),
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.textPrimary,
        )
        Text(
            text = notes,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(tokens.shapes.compactCard)
                .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
                .verticalScroll(rememberScrollState())
                .padding(NuvioTokens.Space.s12),
            style = MaterialTheme.typography.bodySmall,
            color = tokens.colors.textSecondary,
        )
    }
}

@Composable
private fun DownloadProgress(state: AppUpdaterUiState, update: AppUpdate?) {
    val tokens = MaterialTheme.nuvio
    val progress by animateFloatAsState(
        targetValue = (state.downloadProgress ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 180),
        label = "updateDownloadProgress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = tokens.colors.accent,
            trackColor = tokens.colors.textPrimary.copy(alpha = tokens.opacity.hover),
        )
        BodyText(
            state.downloadProgress?.let {
                stringResource(Res.string.updates_downloading_progress, (it * 100).toInt().coerceIn(0, 100))
            } ?: stringResource(Res.string.updates_preparing_download),
        )
        update?.assetSizeBytes?.let { size ->
            Text(
                text = stringResource(Res.string.updates_asset_line, formatFileSize(size), update.assetName),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun BodyText(text: String, color: Color = MaterialTheme.nuvio.colors.textSecondary) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}
