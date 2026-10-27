package com.nuvio.app.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

@Immutable
data class NuvioRailItemState(
    val selected: Boolean,
    val hovered: Boolean,
)

@Immutable
data class NuvioRailItemColors(
    val selectedContainer: Color,
    val hoveredContainer: Color,
    val pressedContainer: Color,
)

object NuvioRailItemDefaults {
    val Shape = RoundedCornerShape(NuvioTokens.Radius.full)
    val ContentPadding = PaddingValues(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10)

    @Composable
    fun accentColors(): NuvioRailItemColors {
        val tokens = MaterialTheme.nuvio
        return NuvioRailItemColors(
            selectedContainer = tokens.colors.accent.copy(alpha = NuvioTokens.Opacity.selected),
            hoveredContainer = tokens.colors.overlayHover,
            pressedContainer = tokens.colors.overlayPressed,
        )
    }

    @Composable
    fun highlightColors(): NuvioRailItemColors {
        val tokens = MaterialTheme.nuvio
        return NuvioRailItemColors(
            selectedContainer = NuvioRailHighlight,
            hoveredContainer = tokens.colors.surfaceCard,
            pressedContainer = tokens.colors.surfacePopover,
        )
    }
}

val NuvioRailHighlight = Color(0xFFF5F5F5)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NuvioRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: NuvioRailItemColors = NuvioRailItemDefaults.accentColors(),
    onSecondaryClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = NuvioRailItemDefaults.ContentPadding,
    role: Role = Role.Tab,
    content: @Composable RowScope.(NuvioRailItemState) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> colors.selectedContainer
            pressed -> colors.pressedContainer
            hovered -> colors.hoveredContainer
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = tokens.motion.fastMillis, easing = tokens.motion.standard),
        label = "railItemContainer",
    )
    val focusColor by animateColorAsState(
        targetValue = if (focused) tokens.colors.focusRing else Color.Transparent,
        animationSpec = tween(durationMillis = tokens.motion.fastMillis),
        label = "railItemFocus",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(NuvioRailItemDefaults.Shape)
            .background(containerColor)
            .border(tokens.borders.medium, focusColor, NuvioRailItemDefaults.Shape)
            .semantics { this.selected = selected }
            .pointerHoverIcon(PointerIcon.Hand)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                role = role,
                onClick = onClick,
                onLongClick = onSecondaryClick,
            )
            .nuvioSecondaryClick(onSecondaryClick)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(NuvioRailItemState(selected = selected, hovered = hovered))
    }
}
