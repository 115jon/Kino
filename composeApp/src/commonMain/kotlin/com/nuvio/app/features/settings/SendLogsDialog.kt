package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.diagnostics.ManualReports
import com.nuvio.app.core.diagnostics.PreparedReport
import com.nuvio.app.core.diagnostics.ReportCollector
import com.nuvio.app.core.ui.DialogButton
import com.nuvio.app.core.ui.DialogButtonStyle
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.send_logs_description_label
import nuvio.composeapp.generated.resources.send_logs_description_placeholder
import nuvio.composeapp.generated.resources.send_logs_done
import nuvio.composeapp.generated.resources.send_logs_email_label
import nuvio.composeapp.generated.resources.send_logs_intro
import nuvio.composeapp.generated.resources.send_logs_retry
import nuvio.composeapp.generated.resources.send_logs_send
import nuvio.composeapp.generated.resources.send_logs_sent_title
import nuvio.composeapp.generated.resources.send_logs_title
import org.jetbrains.compose.resources.stringResource

private const val PreviewDebounceMs = 300L

@Composable
internal fun SendLogsDialog(
    onDismiss: () -> Unit,
    initialPreviewExpanded: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val reportId = rememberSaveable { ReportCollector.newReportId() }
    var description by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable {
        mutableStateOf((AuthRepository.state.value as? AuthState.Authenticated)?.email.orEmpty())
    }
    var includeLogs by rememberSaveable { mutableStateOf(true) }
    var previewExpanded by rememberSaveable { mutableStateOf(initialPreviewExpanded) }
    var preview by remember { mutableStateOf<PreparedReport?>(null) }
    var phase by remember { mutableStateOf<SendLogsPhase>(SendLogsPhase.Editing) }
    val sending = phase == SendLogsPhase.Sending

    LaunchedEffect(previewExpanded, includeLogs, description, email) {
        if (!previewExpanded) return@LaunchedEffect
        delay(PreviewDebounceMs)
        preview = ManualReports.prepare(reportId, description, email, includeLogs)
    }

    val send: () -> Unit = {
        phase = SendLogsPhase.Sending
        scope.launch {
            val prepared = ManualReports.prepare(reportId, description, email, includeLogs)
            phase = ManualReports.send(prepared).toSendLogsPhase()
        }
    }

    val sent = phase as? SendLogsPhase.Sent
    DialogSurface(
        onDismissRequest = { if (!sending) onDismiss() },
        title = stringResource(if (sent != null) Res.string.send_logs_sent_title else Res.string.send_logs_title),
        properties = DialogProperties(dismissOnClickOutside = false),
    ) {
        if (sent != null) {
            SendLogsSentContent(reportId = sent.reportId)
            SendLogsFooter {
                DialogButton(
                    text = stringResource(Res.string.send_logs_done),
                    onClick = onDismiss,
                    style = DialogButtonStyle.Primary,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s16),
            ) {
                Text(
                    text = stringResource(Res.string.send_logs_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.nuvio.colors.textSecondary,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !sending,
                    label = { Text(stringResource(Res.string.send_logs_description_label)) },
                    placeholder = { Text(stringResource(Res.string.send_logs_description_placeholder)) },
                    minLines = 4,
                    maxLines = 8,
                    colors = settingsTextFieldColors(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !sending,
                    singleLine = true,
                    label = { Text(stringResource(Res.string.send_logs_email_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = settingsTextFieldColors(),
                )
                IncludeLogsRow(checked = includeLogs, enabled = !sending, onCheckedChange = { includeLogs = it })
                SendLogsPreview(
                    expanded = previewExpanded,
                    onExpandedChange = { previewExpanded = it },
                    prepared = preview,
                )
            }
            (phase as? SendLogsPhase.Failed)?.let { failed -> SendLogsError(stringResource(failed.message)) }
            SendLogsFooter {
                DialogButton(
                    text = stringResource(Res.string.action_cancel),
                    onClick = onDismiss,
                    enabled = !sending,
                )
                DialogButton(
                    text = stringResource(if (phase is SendLogsPhase.Failed) Res.string.send_logs_retry else Res.string.send_logs_send),
                    onClick = send,
                    style = DialogButtonStyle.Primary,
                    enabled = description.isNotBlank(),
                    loading = sending,
                )
            }
        }
    }
}
