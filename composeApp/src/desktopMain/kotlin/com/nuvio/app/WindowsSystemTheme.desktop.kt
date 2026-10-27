package com.nuvio.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object WindowsSystemTheme {
    private val _darkMode = MutableStateFlow(readWindowsDarkMode())
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()

    init {
        subscribeToWindowsThemeChanges {
            _darkMode.value = readWindowsDarkMode()
        }
    }
}
