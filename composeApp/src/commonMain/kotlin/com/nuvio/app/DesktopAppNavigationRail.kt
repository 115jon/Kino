package com.nuvio.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioDesktopNavigationItem
import com.nuvio.app.core.ui.NuvioDesktopNavigationRail
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_brand_name
import nuvio.composeapp.generated.resources.app_logo_wordmark
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_profile
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DesktopAppNavigationRail(
    selectedTab: AppScreenTab,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
) {
    val profileState by ProfileRepository.state.collectAsStateWithLifecycle()

    NuvioDesktopNavigationRail(
        header = {
            Image(
                painter = painterResource(Res.drawable.app_logo_wordmark),
                contentDescription = stringResource(Res.string.app_brand_name),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                alignment = Alignment.CenterStart,
                contentScale = ContentScale.Fit,
            )
        },
        content = {
            NuvioDesktopNavigationItem(
                selected = selectedTab == AppScreenTab.Home,
                onClick = { onTabSelected(AppScreenTab.Home) },
                label = stringResource(Res.string.compose_nav_home),
                icon = Icons.Filled.Home,
            )
            NuvioDesktopNavigationItem(
                selected = selectedTab == AppScreenTab.Search,
                onClick = { onTabSelected(AppScreenTab.Search) },
                label = stringResource(Res.string.compose_nav_search),
                icon = painterResource(Res.drawable.sidebar_search),
            )
            NuvioDesktopNavigationItem(
                selected = selectedTab == AppScreenTab.Library,
                onClick = { onTabSelected(AppScreenTab.Library) },
                label = stringResource(Res.string.compose_nav_library),
                icon = painterResource(Res.drawable.sidebar_library),
            )
        },
        footer = {
            NuvioDesktopNavigationItem(
                selected = selectedTab == AppScreenTab.Settings,
                onClick = { onTabSelected(AppScreenTab.Settings) },
                label = profileState.activeProfile?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: stringResource(Res.string.compose_nav_profile),
            ) {
                ProfileSwitcherTab(
                    selected = selectedTab == AppScreenTab.Settings,
                    onClick = { onTabSelected(AppScreenTab.Settings) },
                    onProfileSelected = onProfileSelected,
                    onAddProfileRequested = onAddProfileRequested,
                    modifier = Modifier.size(28.dp),
                    expandOnClick = true,
                )
            }
        },
    )
}
