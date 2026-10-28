package com.nuvio.app.features.search

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.isDesktopPlatform

private val DesktopSearchFieldMaxWidth = 720.dp

internal fun Modifier.searchFieldFrame(): Modifier =
    if (isDesktopPlatform) widthIn(max = DesktopSearchFieldMaxWidth) else this

internal fun Modifier.clearSearchOnEscape(
    enabled: Boolean,
    onClear: () -> Unit,
): Modifier =
    if (!enabled) {
        this
    } else {
        onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onClear()
                true
            } else {
                false
            }
        }
    }
