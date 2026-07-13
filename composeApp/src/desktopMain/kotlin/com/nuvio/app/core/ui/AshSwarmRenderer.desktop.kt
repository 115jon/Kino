package com.nuvio.app.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

internal actual val ashSwarmMaxGrainBudget: Int = 26000

internal actual class AshSwarmRenderer actual constructor(maxGrains: Int) {
    actual fun draw(
        scope: DrawScope,
        count: Int,
        centersX: FloatArray,
        centersY: FloatArray,
        halfWidths: FloatArray,
        halfHeights: FloatArray,
        colors: IntArray,
    ) {
        for (i in 0 until count) {
            val halfWidth = halfWidths[i]
            val halfHeight = halfHeights[i]
            scope.drawRect(
                color = Color(colors[i]),
                topLeft = Offset(centersX[i] - halfWidth, centersY[i] - halfHeight),
                size = Size(halfWidth * 2f, halfHeight * 2f),
            )
        }
    }
}
