package com.nuvio.app.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
internal data class NuvioThemeSource(
    val appTheme: AppTheme,
    val amoled: Boolean,
    val customThemeColors: CustomThemeColors,
)

@Immutable
data class NuvioChromeColors(
    val darkTheme: Boolean,
    val background: Color,
    val foreground: Color,
)

val LocalNuvioDarkTheme = staticCompositionLocalOf { true }

internal val LocalNuvioThemeSource = staticCompositionLocalOf {
    NuvioThemeSource(AppTheme.WHITE, amoled = false, customThemeColors = CustomThemeColors.Default)
}

internal fun resolveNuvioPalette(
    appTheme: AppTheme,
    customThemeColors: CustomThemeColors,
    darkTheme: Boolean,
): ThemeColorPalette {
    val palette = ThemeColors.getColorPalette(appTheme, customThemeColors)
    return if (darkTheme) palette else palette.toLightPalette()
}

fun nuvioChromeColors(
    appTheme: AppTheme,
    customThemeColors: CustomThemeColors,
    amoled: Boolean,
    darkTheme: Boolean,
): NuvioChromeColors {
    val palette = resolveNuvioPalette(appTheme, customThemeColors, darkTheme)
    val colors = defaultNuvioThemeTokens(palette, amoled = amoled && darkTheme, colorScheme = null, darkTheme = darkTheme).colors
    return NuvioChromeColors(darkTheme = darkTheme, background = colors.background, foreground = colors.textPrimary)
}

@Composable
fun NuvioDarkTheme(content: @Composable () -> Unit) {
    if (LocalNuvioDarkTheme.current) {
        content()
        return
    }
    val source = LocalNuvioThemeSource.current
    NuvioTheme(
        darkTheme = true,
        appTheme = source.appTheme,
        amoled = source.amoled,
        customThemeColors = source.customThemeColors,
        content = content,
    )
}

@Composable
fun Color.legibleOnBackground(): Color =
    if (LocalNuvioDarkTheme.current) this else darkenedForContrast(MaterialTheme.nuvio.colors.background, 4.5f)
