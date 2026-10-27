package com.nuvio.app.features.settings

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.MaterialTheme
import com.nuvio.app.core.build.KinoFeaturePolicy
import com.nuvio.app.core.ui.appTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_logo_wordmark
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun AppBrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    icon: AppIconOption? = null,
) {
    val state by remember {
        AppIconRepository.ensureLoaded()
        AppIconRepository.state
    }.collectAsStateWithLifecycle()
    Image(
        painter = painterResource(
            if (KinoFeaturePolicy.alternateAppIconsEnabled) {
                icon?.wordmarkResource ?: MaterialTheme.appTheme.wordmarkResource(state.selected)
            } else {
                Res.drawable.app_logo_wordmark
            },
        ),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
