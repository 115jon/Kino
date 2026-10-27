package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioChromeColors
import com.nuvio.app.core.ui.ThemeMode
import com.nuvio.app.core.ui.nuvioChromeColors
import com.nuvio.app.core.ui.systemDarkTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ThemeModeRepository {
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()
    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
        _mode.value = ThemeMode.fromKey(ThemeSettingsStorage.loadThemeMode())
    }

    fun setMode(mode: ThemeMode) {
        ensureLoaded()
        if (_mode.value == mode) return
        _mode.value = mode
        ThemeSettingsStorage.saveThemeMode(mode.name)
    }
}

@Composable
fun rememberDarkThemeEnabled(): Boolean {
    val mode by remember {
        ThemeModeRepository.ensureLoaded()
        ThemeModeRepository.mode
    }.collectAsStateWithLifecycle()
    return mode.isDark(systemDarkTheme())
}

@Composable
fun rememberAppChromeColors(): NuvioChromeColors {
    val darkTheme = rememberDarkThemeEnabled()
    val appTheme by remember {
        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.selectedTheme
    }.collectAsStateWithLifecycle()
    val amoled by ThemeSettingsRepository.amoledEnabled.collectAsStateWithLifecycle()
    val customThemeColors by ThemeSettingsRepository.customThemeColors.collectAsStateWithLifecycle()
    return remember(appTheme, customThemeColors, amoled, darkTheme) {
        nuvioChromeColors(appTheme, customThemeColors, amoled, darkTheme)
    }
}
