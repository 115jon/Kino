package com.nuvio.app.harness

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nuvio.app.App
import com.nuvio.app.AppScreenTab
import com.nuvio.app.DesktopWindowChrome
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.ThemeMode
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.compose.configureWindowsComposeVideoRenderApi
import com.nuvio.app.features.player.compose.configureWindowsVideoSurfaceSelection
import com.nuvio.app.features.settings.ThemeModeRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.updater.AppUpdaterPlatform
import com.nuvio.app.features.updater.AppUpdaterRepository
import com.nuvio.app.features.updater.UpdateChannel
import com.nuvio.app.navigation.DetailRoute
import com.nuvio.app.navigation.PlayerRoute
import com.nuvio.app.navigation.SettingsPageRoute
import com.nuvio.app.navigation.TabsRoute
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

fun main() {
    configureWindowsVideoSurfaceSelection()
    System.setProperty("compose.interop.blending", "false")
    configureWindowsComposeVideoRenderApi()
    if (System.getProperty("kino.theme.noupdate") == "true") {
        runBlocking {
            AppUpdaterRepository.getLatestChannelUpdate(UpdateChannel.STABLE).getOrNull()?.tag?.let(AppUpdaterPlatform::setIgnoredTag)
        }
    }
    val control = System.getProperty("kino.theme.control")?.let(::File)
    val tab = AppScreenTab.fromName(System.getProperty("kino.theme.tab") ?: "Home")
    val routeSpec = System.getProperty("kino.theme.route").orEmpty().split(':', limit = 3)
    val route = when (routeSpec.first()) {
        "player" -> PlayerRoute(
            PlayerLaunchStore.put(
                PlayerLaunch(
                    profileId = 1,
                    title = "Harness",
                    sourceUrl = routeSpec.drop(1).joinToString(":"),
                    streamTitle = "Harness",
                    providerName = "Local",
                    parentMetaId = "harness",
                    parentMetaType = "movie",
                ),
            ),
        )
        "detail" -> DetailRoute(type = routeSpec[1], id = routeSpec[2])
        "settings" -> SettingsPageRoute(pageName = routeSpec[1], title = routeSpec[1])
        else -> TabsRoute
    }
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kino",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            LaunchedEffect(Unit) {
                System.getProperty("kino.theme.preset")?.let { ThemeSettingsRepository.setTheme(AppTheme.valueOf(it)) }
            }
            LaunchedEffect(Unit) {
                System.getProperty("kino.theme.addon")?.let { url ->
                    delay(3000)
                    AddonRepository.addAddon(url)
                }
            }
            LaunchedEffect(Unit) {
                var last = ""
                while (true) {
                    val command = control?.takeIf(File::exists)?.readText()?.trim().orEmpty()
                    if (command != last) {
                        last = command
                        ThemeMode.entries.firstOrNull { it.name == command }?.let(ThemeModeRepository::setMode)
                    }
                    delay(250)
                }
            }
            DesktopWindowChrome(window)
            App(initialTab = tab, initialRoute = route, bypassAppGate = true)
        }
    }
}
