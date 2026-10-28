package com.nuvio.app.features.settings

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.isDesktopPlatform
import com.nuvio.app.core.ui.nuvio

internal val settingsDesktopLayout: Boolean
    get() = isDesktopPlatform

@Immutable
internal data class SettingsRowMetrics(
    val iconSize: Dp,
    val painterSize: Dp,
    val iconGap: Dp,
    val verticalPadding: Dp,
    val horizontalPadding: Dp,
    val titleStyle: TextStyle,
    val descriptionStyle: TextStyle,
    val showChevron: Boolean,
    val dividerInset: Dp,
    val cardShape: Shape,
    val interaction: Modifier,
)

@Composable
internal fun settingsRowMetrics(isTablet: Boolean): SettingsRowMetrics {
    val typography = MaterialTheme.typography
    val tokens = MaterialTheme.nuvio
    return when {
        settingsDesktopLayout -> SettingsRowMetrics(
            iconSize = NuvioTokens.Space.s32,
            painterSize = NuvioTokens.Icon.md,
            iconGap = NuvioTokens.Space.s12,
            verticalPadding = NuvioTokens.Space.s10,
            horizontalPadding = NuvioTokens.Space.s16,
            titleStyle = typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            descriptionStyle = typography.bodyMedium.copy(
                fontSize = NuvioTokens.Type.bodySm,
                lineHeight = NuvioTokens.LineHeight.bodySm,
            ),
            showChevron = true,
            dividerInset = NuvioTokens.Space.s16,
            cardShape = tokens.shapes.compactCard,
            interaction = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
        isTablet -> SettingsRowMetrics(
            iconSize = 42.dp,
            painterSize = 28.dp,
            iconGap = 16.dp,
            verticalPadding = 16.dp,
            horizontalPadding = 20.dp,
            titleStyle = typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            descriptionStyle = typography.bodyMedium,
            showChevron = false,
            dividerInset = NuvioTokens.Space.s80 - NuvioTokens.Space.s2,
            cardShape = RoundedCornerShape(NuvioTokens.Radius.xl),
            interaction = Modifier,
        )
        else -> SettingsRowMetrics(
            iconSize = 36.dp,
            painterSize = 24.dp,
            iconGap = 14.dp,
            verticalPadding = 14.dp,
            horizontalPadding = 16.dp,
            titleStyle = typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            descriptionStyle = typography.bodyMedium,
            showChevron = false,
            dividerInset = NuvioTokens.Space.s64 + NuvioTokens.Space.s2,
            cardShape = tokens.shapes.compactCard,
            interaction = Modifier,
        )
    }
}

@Composable
internal fun settingsPageTitleStyle(): TextStyle {
    val headline = MaterialTheme.typography.headlineLarge
    return if (settingsDesktopLayout) {
        headline.copy(fontSize = NuvioTokens.Type.titleMd, lineHeight = NuvioTokens.LineHeight.titleMd)
    } else {
        headline
    }
}
