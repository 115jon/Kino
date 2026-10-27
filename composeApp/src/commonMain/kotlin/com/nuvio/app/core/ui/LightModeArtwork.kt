package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

val LightModeArtworkFilter = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.574f, -1.43f, -0.144f, 0f, 255f,
            -0.426f, -0.43f, -0.144f, 0f, 255f,
            -0.426f, -1.43f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)
