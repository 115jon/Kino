package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal val sheetsOpenAsDesktopDialogs: Boolean
    get() = isDesktopPlatform

@Composable
internal fun NuvioDesktopSheetDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.nuvio.colors.surfaceSheet,
    contentColor: Color = MaterialTheme.nuvio.colors.textPrimary,
    fullHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val maxHeight = maxHeight * if (fullHeight) 0.92f else 0.8f
            CompositionLocalProvider(
                LocalBottomInsetsConsumed provides true,
                LocalContentColor provides contentColor,
            ) {
                Column(
                    modifier = modifier
                        .padding(tokens.spacing.screenHorizontal)
                        .widthIn(max = tokens.components.sheetMaxWidth)
                        .heightIn(max = maxHeight)
                        .clip(tokens.shapes.dialog)
                        .background(containerColor)
                        .border(tokens.borders.thin, SurfaceEdge, tokens.shapes.dialog),
                    content = content,
                )
            }
        }
    }
}
