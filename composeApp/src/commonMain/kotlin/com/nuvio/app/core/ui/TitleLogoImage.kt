package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter

private val TitleLogoHalo = ColorFilter.tint(Color.Black.copy(alpha = 0.75f), BlendMode.SrcIn)

@Composable
fun TitleLogoImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    onError: ((AsyncImagePainter.State.Error) -> Unit)? = null,
) {
    if (LocalNuvioDarkTheme.current) {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            modifier = modifier,
            alignment = alignment,
            contentScale = contentScale,
            onError = onError,
        )
        return
    }
    Box(modifier = modifier, propagateMinConstraints = true) {
        AsyncImage(
            model = model,
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .blur(8.dp, BlurredEdgeTreatment.Unbounded),
            alignment = alignment,
            contentScale = contentScale,
            colorFilter = TitleLogoHalo,
        )
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            alignment = alignment,
            contentScale = contentScale,
            onError = onError,
        )
    }
}
