package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import com.nuvio.app.core.ui.DialogButtons
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.isDesktopPlatform
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.send_logs_copied
import nuvio.composeapp.generated.resources.send_logs_copy
import nuvio.composeapp.generated.resources.send_logs_include_logs
import nuvio.composeapp.generated.resources.send_logs_include_logs_detail
import nuvio.composeapp.generated.resources.send_logs_report_id
import nuvio.composeapp.generated.resources.send_logs_sent_body
import org.jetbrains.compose.resources.stringResource

private const val CopiedResetMs = 2_000L

@Composable
internal fun SendLogsFooter(content: @Composable () -> Unit) {
    if (isDesktopPlatform) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = NuvioTokens.Space.s8),
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    } else {
        DialogButtons(content = content)
    }
}

@Composable
internal fun SendLogsSentContent(reportId: String) {
    val tokens = MaterialTheme.nuvio
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(CopiedResetMs)
            copied = false
        }
    }
    Text(
        text = stringResource(Res.string.send_logs_sent_body),
        style = MaterialTheme.typography.bodyMedium,
        color = tokens.colors.textSecondary,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .background(tokens.colors.textPrimary.copy(alpha = tokens.opacity.subtle))
            .padding(start = NuvioTokens.Space.s14, end = NuvioTokens.Space.s4, top = NuvioTokens.Space.s10, bottom = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
        ) {
            Text(
                text = stringResource(Res.string.send_logs_report_id),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
            )
            SelectionContainer {
                Text(
                    text = reportId,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = tokens.colors.textPrimary,
                )
            }
        }
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(reportId))
                copied = true
            },
        ) {
            Icon(
                imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = null,
                tint = tokens.colors.accent,
                modifier = Modifier.size(tokens.icons.sm),
            )
            Text(
                text = stringResource(if (copied) Res.string.send_logs_copied else Res.string.send_logs_copy),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.accent,
                modifier = Modifier.padding(start = NuvioTokens.Space.s6),
            )
        }
    }
}

@Composable
internal fun IncludeLogsRow(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = CheckboxDefaults.colors(
                checkedColor = tokens.colors.accent,
                uncheckedColor = tokens.colors.textMuted,
                checkmarkColor = tokens.colors.onAccent,
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
            Text(
                text = stringResource(Res.string.send_logs_include_logs),
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.send_logs_include_logs_detail),
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
    }
}

@Composable
internal fun SendLogsError(message: String) {
    val tokens = MaterialTheme.nuvio
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        modifier = Modifier.padding(top = NuvioTokens.Space.s4),
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = tokens.colors.danger,
            modifier = Modifier.size(tokens.icons.sm),
        )
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = tokens.colors.danger)
    }
}
