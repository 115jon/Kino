package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.NuvioRailItem
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_category_about
import nuvio.composeapp.generated.resources.compose_settings_page_account
import nuvio.composeapp.generated.resources.compose_settings_page_advanced
import nuvio.composeapp.generated.resources.compose_settings_page_appearance
import nuvio.composeapp.generated.resources.compose_settings_page_content_discovery
import nuvio.composeapp.generated.resources.compose_settings_page_integrations
import nuvio.composeapp.generated.resources.compose_settings_page_notifications
import nuvio.composeapp.generated.resources.compose_settings_page_playback
import nuvio.composeapp.generated.resources.compose_settings_page_root
import nuvio.composeapp.generated.resources.compose_settings_page_tracking
import nuvio.composeapp.generated.resources.compose_settings_root_switch_profile_title
import nuvio.composeapp.generated.resources.party_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val DesktopSidebarWidth = 224.dp
private val DesktopContentMaxWidth = 880.dp
private val DesktopContentHorizontalPadding = NuvioTokens.Space.s32
private val DesktopContentTopPadding = NuvioTokens.Space.s28
private val DesktopLandingPage = SettingsPage.Account

private enum class DesktopSettingsEntry(
    val labelRes: StringResource,
    val icon: ImageVector,
    val page: SettingsPage?,
) {
    Account(Res.string.compose_settings_page_account, Icons.Rounded.AccountCircle, SettingsPage.Account),
    Appearance(Res.string.compose_settings_page_appearance, Icons.Rounded.Palette, SettingsPage.Appearance),
    ContentDiscovery(Res.string.compose_settings_page_content_discovery, Icons.Rounded.Extension, SettingsPage.ContentDiscovery),
    Playback(Res.string.compose_settings_page_playback, Icons.Rounded.PlayArrow, SettingsPage.Playback),
    Integrations(Res.string.compose_settings_page_integrations, Icons.Rounded.Link, SettingsPage.Integrations),
    Tracking(Res.string.compose_settings_page_tracking, Icons.Default.Sync, SettingsPage.TraktAuthentication),
    Notifications(Res.string.compose_settings_page_notifications, Icons.Rounded.Notifications, SettingsPage.Notifications),
    Advanced(Res.string.compose_settings_page_advanced, Icons.Rounded.Tune, SettingsPage.Advanced),
    About(Res.string.compose_settings_category_about, Icons.Rounded.Info, null),
}

private fun SettingsPage.topLevelPage(): SettingsPage {
    var current = this
    while (true) {
        val parent = current.parentPage ?: return current
        if (parent == SettingsPage.Root) return current
        current = parent
    }
}

internal fun SettingsPage.desktopDisplayedPage(category: SettingsCategory): SettingsPage =
    if (settingsDesktopLayout && this == SettingsPage.Root && category != SettingsCategory.About) {
        DesktopLandingPage
    } else {
        this
    }

internal fun settingsPaneTopOffset(tabletOffset: Dp): Dp =
    if (settingsDesktopLayout) DesktopContentTopPadding else tabletOffset

internal val settingsPaneHorizontalPadding: Dp
    get() = if (settingsDesktopLayout) DesktopContentHorizontalPadding else 40.dp

internal fun Modifier.settingsPane(): Modifier =
    if (settingsDesktopLayout) {
        fillMaxHeight().widthIn(max = DesktopContentMaxWidth + DesktopContentHorizontalPadding * 2)
    } else {
        fillMaxSize()
    }

internal fun settingsHeaderShowsBack(previousPage: SettingsPage?): Boolean =
    previousPage != null && !(settingsDesktopLayout && previousPage == SettingsPage.Root)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopSettingsEscapeHandler(
    page: SettingsPage,
    onBack: () -> Unit,
) {
    if (!settingsDesktopLayout) return
    val active = LocalScreenActive.current
    BackHandler(enabled = active && settingsHeaderShowsBack(page.previousPage()), onBack = onBack)
}

@Composable
internal fun DesktopSettingsSidebar(
    page: SettingsPage,
    activeCategory: SettingsCategory,
    onOpenPage: (SettingsPage) -> Unit,
    onOpenAbout: () -> Unit,
    onSwitchProfile: (() -> Unit)?,
    onWatchParty: (() -> Unit)?,
) {
    val tokens = MaterialTheme.nuvio
    val selectedEntry = when {
        page == SettingsPage.Root && activeCategory == SettingsCategory.About -> DesktopSettingsEntry.About
        else -> page.topLevelPage().let { top -> DesktopSettingsEntry.entries.firstOrNull { it.page == top } }
    }
    Column(
        modifier = Modifier
            .width(DesktopSidebarWidth)
            .fillMaxHeight()
            .background(tokens.colors.background)
            .drawBehind {
                drawRect(
                    color = tokens.colors.borderSubtle,
                    topLeft = Offset(x = size.width - tokens.borders.thin.toPx(), y = 0f),
                    size = Size(width = tokens.borders.thin.toPx(), height = size.height),
                )
            }
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s20),
    ) {
        Text(
            text = stringResource(Res.string.compose_settings_page_root),
            modifier = Modifier.padding(
                start = NuvioTokens.Space.s12,
                end = NuvioTokens.Space.s12,
                top = NuvioTokens.Space.s4,
                bottom = NuvioTokens.Space.s16,
            ),
            style = settingsPageTitleStyle(),
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
        ) {
            DesktopSettingsEntry.entries.forEach { entry ->
                DesktopSettingsSidebarItem(
                    label = stringResource(entry.labelRes),
                    icon = entry.icon,
                    selected = entry == selectedEntry,
                    onClick = {
                        val target = entry.page
                        if (target != null) onOpenPage(target) else onOpenAbout()
                    },
                )
            }
        }
        if (onSwitchProfile != null || onWatchParty != null) {
            Spacer(modifier = Modifier.size(NuvioTokens.Space.s12))
        }
        if (onWatchParty != null) {
            DesktopSettingsSidebarItem(
                label = stringResource(Res.string.party_title),
                icon = Icons.Rounded.Groups,
                selected = false,
                onClick = onWatchParty,
            )
        }
        if (onSwitchProfile != null) {
            DesktopSettingsSidebarItem(
                label = stringResource(Res.string.compose_settings_root_switch_profile_title),
                icon = Icons.Rounded.People,
                selected = false,
                onClick = onSwitchProfile,
            )
        }
    }
}

@Composable
private fun DesktopSettingsSidebarItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NuvioRailItem(
        selected = selected,
        onClick = onClick,
        role = Role.Tab,
        contentPadding = PaddingValues(
            horizontal = NuvioTokens.Space.s12,
            vertical = NuvioTokens.Space.s8,
        ),
    ) { state ->
        val contentColor = when {
            state.selected -> tokens.colors.accent
            state.hovered -> tokens.colors.textSecondary
            else -> tokens.colors.textMuted
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(NuvioTokens.Icon.md),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (state.selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (state.selected) tokens.colors.textPrimary else contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
