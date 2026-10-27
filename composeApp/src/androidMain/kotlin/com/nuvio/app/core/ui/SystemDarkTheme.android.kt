package com.nuvio.app.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

@Composable
actual fun systemDarkTheme(): Boolean = isSystemInDarkTheme()
