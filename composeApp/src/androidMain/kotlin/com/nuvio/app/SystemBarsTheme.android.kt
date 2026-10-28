package com.nuvio.app

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import com.nuvio.app.features.settings.rememberAppChromeColors

private const val DarkNavigationScrim = 0xFF020404.toInt()

@Composable
internal fun ComponentActivity.SystemBarsTheme() {
    val chrome = rememberAppChromeColors()
    LaunchedEffect(chrome) {
        val background = chrome.background.toArgb()
        enableEdgeToEdge(
            statusBarStyle = if (chrome.darkTheme) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            },
            navigationBarStyle = if (chrome.darkTheme) {
                SystemBarStyle.dark(DarkNavigationScrim)
            } else {
                SystemBarStyle.light(background, background)
            },
        )
        window.decorView.setBackgroundColor(background)
    }
}
