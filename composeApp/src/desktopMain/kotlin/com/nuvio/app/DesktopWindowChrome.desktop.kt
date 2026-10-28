package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.nuvio.app.features.player.compose.findSkiaLayer
import com.nuvio.app.features.settings.rememberAppChromeColors
import java.awt.Color as AwtColor

@Composable
internal fun DesktopWindowChrome(window: ComposeWindow) {
    val chrome = rememberAppChromeColors()
    val background = chrome.background.toAwtColor()
    SideEffect {
        window.background = background
        window.contentPane.background = background
        window.rootPane.background = background
        findSkiaLayer(window)?.background = background
    }
    LaunchedEffect(window, chrome) {
        if (isWindowsPlatform()) {
            applyWindowsTitleBarTheme(window, chrome.darkTheme, background, chrome.foreground.toAwtColor())
        }
    }
}

private fun Color.toAwtColor(): AwtColor = AwtColor(toArgb(), false)
