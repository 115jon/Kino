package com.nuvio.app.features.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.diagnostics.PreparedReport
import com.nuvio.app.core.diagnostics.ReportPreviewGroup
import com.nuvio.app.core.diagnostics.ReportPreviewSection
import com.nuvio.app.core.diagnostics.previewSections
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.send_logs_group_account
import nuvio.composeapp.generated.resources.send_logs_group_app
import nuvio.composeapp.generated.resources.send_logs_group_device
import nuvio.composeapp.generated.resources.send_logs_group_logs
import nuvio.composeapp.generated.resources.send_logs_group_report
import nuvio.composeapp.generated.resources.send_logs_group_runtime
import nuvio.composeapp.generated.resources.send_logs_group_settings
import nuvio.composeapp.generated.resources.send_logs_preview_loading
import nuvio.composeapp.generated.resources.send_logs_whats_included
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SendLogsPreview(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    prepared: PreparedReport?,
) {
    val tokens = MaterialTheme.nuvio
    val rotation by animateFloatAsState(if (expanded) 90f else 0f)
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8)) {
        Row(
            modifier = Modifier
                .clip(tokens.shapes.compactCard)
                .toggleable(value = expanded, role = Role.Button, onValueChange = onExpandedChange)
                .padding(vertical = NuvioTokens.Space.s4, horizontal = NuvioTokens.Space.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = tokens.colors.textMuted,
                modifier = Modifier.size(tokens.icons.md).rotate(rotation),
            )
            Text(
                text = stringResource(Res.string.send_logs_whats_included),
                style = MaterialTheme.typography.labelLarge,
                color = tokens.colors.textPrimary,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .border(tokens.borders.hairline, tokens.colors.borderSubtle, tokens.shapes.compactCard)
                    .verticalScroll(rememberScrollState())
                    .padding(NuvioTokens.Space.s12),
            ) {
                if (prepared == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                    ) {
                        NuvioLoadingIndicator(modifier = Modifier.size(tokens.icons.sm), color = tokens.colors.textMuted)
                        Text(
                            text = stringResource(Res.string.send_logs_preview_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.textMuted,
                        )
                    }
                } else {
                    val sections = remember(prepared) { prepared.previewSections() }
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                            sections.forEach { section -> PreviewSection(section) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSection(section: ReportPreviewSection) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2)) {
        Text(
            text = stringResource(section.group.label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.textPrimary,
            modifier = Modifier.padding(bottom = NuvioTokens.Space.s2),
        )
        section.rows.forEach { (key, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12)) {
                Text(
                    text = key,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = tokens.colors.textMuted,
                    modifier = Modifier.weight(0.42f),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = tokens.colors.textSecondary,
                    modifier = Modifier.weight(0.58f),
                )
            }
        }
    }
}

private val ReportPreviewGroup.label: StringResource
    get() = when (this) {
        ReportPreviewGroup.Report -> Res.string.send_logs_group_report
        ReportPreviewGroup.App -> Res.string.send_logs_group_app
        ReportPreviewGroup.Device -> Res.string.send_logs_group_device
        ReportPreviewGroup.Runtime -> Res.string.send_logs_group_runtime
        ReportPreviewGroup.Account -> Res.string.send_logs_group_account
        ReportPreviewGroup.Settings -> Res.string.send_logs_group_settings
        ReportPreviewGroup.Logs -> Res.string.send_logs_group_logs
    }
