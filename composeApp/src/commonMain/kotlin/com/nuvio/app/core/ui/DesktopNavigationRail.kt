package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val DesktopRailIconSize = NuvioTokens.Icon.lg

@Composable
fun NuvioDesktopNavigationRail(
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier
            .widthIn(min = 220.dp, max = 240.dp)
            .fillMaxHeight()
            .background(tokens.colors.background)
            .drawBehind {
                drawRect(
                    color = tokens.colors.borderSubtle,
                    topLeft = Offset(x = size.width - tokens.borders.thin.toPx(), y = 0f),
                    size = Size(width = tokens.borders.thin.toPx(), height = size.height),
                )
            }
            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Vertical))
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s18),
    ) {
        Column(modifier = Modifier.padding(horizontal = NuvioTokens.Space.s6)) {
            header()
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = NuvioTokens.Space.s28),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            content()
        }
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4)) {
            footer()
        }
    }
}

@Composable
fun NuvioDesktopNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    NuvioDesktopNavigationItem(
        selected = selected,
        onClick = onClick,
        label = label,
        icon = rememberVectorPainter(icon),
        modifier = modifier,
    )
}

@Composable
fun NuvioDesktopNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    icon: Painter,
    modifier: Modifier = Modifier,
) {
    val palette = MaterialTheme.themePalette
    NuvioDesktopNavigationItem(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
    ) { iconColor ->
        Icon(
            painter = icon,
            contentDescription = null,
            tint = if (selected) Color.White else iconColor,
            modifier = Modifier
                .size(DesktopRailIconSize)
                .then(if (selected) Modifier.gradientMask(palette.accentBrush()) else Modifier),
        )
    }
}

@Composable
fun NuvioDesktopNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: @Composable (iconColor: Color) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NuvioRailItem(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = NuvioTokens.Space.s14,
            vertical = NuvioTokens.Space.s10,
        ),
    ) { state ->
        val contentColor by animateColorAsState(
            targetValue = when {
                state.selected -> tokens.colors.accent
                state.hovered -> tokens.colors.textSecondary
                else -> tokens.colors.textMuted
            },
            label = "desktopNavContent",
        )
        Box(
            modifier = Modifier.size(DesktopRailIconSize + NuvioTokens.Space.s4),
            contentAlignment = Alignment.Center,
        ) {
            icon(contentColor)
        }
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
