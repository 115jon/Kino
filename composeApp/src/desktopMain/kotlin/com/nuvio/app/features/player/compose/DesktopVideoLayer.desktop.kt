package com.nuvio.app.features.player.compose

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode

@Composable
internal fun DesktopVideoLayer(
    presenter: ComposeVideoPresenter,
    stats: ComposeVideoStats,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier = modifier
            .onSizeChanged { size -> presenter.setTargetSize(size.width, size.height) }
            .graphicsLayer()
            .drawBehind {
                presenter.frameSerial.intValue
                if (!presenter.visible.value) return@drawBehind
                val image = presenter.acquireImage() ?: return@drawBehind
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawImageRect(
                        image,
                        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                        Rect.makeWH(size.width, size.height),
                        SamplingMode.LINEAR,
                        null,
                        true,
                    )
                }
                stats.recordDraw()
            },
    )
}
