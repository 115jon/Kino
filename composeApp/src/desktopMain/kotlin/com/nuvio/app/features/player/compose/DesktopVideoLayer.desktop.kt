package com.nuvio.app.features.player.compose

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.onSizeChanged
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

@Composable
internal fun DesktopVideoLayer(
    renderer: MpvGlVideoRenderer,
    stats: ComposeVideoStats,
    visible: State<Boolean>,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(renderer) {
        while (true) {
            renderer.awaitRenderRequest()
            withFrameNanos { renderer.renderNow() }
        }
    }
    Spacer(
        modifier = modifier
            .onSizeChanged { size -> renderer.setTargetSize(size.width, size.height) }
            .drawBehind {
                val version = renderer.frameVersion.intValue
                if (!visible.value) return@drawBehind
                val image = renderer.currentImage() ?: return@drawBehind
                drawIntoCanvas { canvas ->
                    canvas.skiaCanvas.drawImageRect(
                        image,
                        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                        Rect.makeWH(size.width, size.height),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    )
                }
                stats.recordDraw()
                renderer.onFrameDrawn(version)
            },
    )
}
