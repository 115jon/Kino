package com.nuvio.app.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import com.nuvio.app.WindowsSystemTheme
import com.nuvio.app.isWindowsPlatform

@Composable
actual fun systemDarkTheme(): Boolean =
    if (isWindowsPlatform()) WindowsSystemTheme.darkMode.collectAsState().value else isSystemInDarkTheme()
