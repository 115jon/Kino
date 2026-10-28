package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

private val BlankPlayerPointer: PointerIcon by lazy {
    val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "kino-hidden-pointer"))
}

internal actual fun Modifier.hidePlayerPointerWhen(hidden: Boolean): Modifier =
    if (hidden) pointerHoverIcon(BlankPlayerPointer, overrideDescendants = true) else this
