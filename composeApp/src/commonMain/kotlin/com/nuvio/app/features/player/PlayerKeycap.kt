package com.nuvio.app.features.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.nuvioTypeScale

private val KeycapShape = RoundedCornerShape(6.dp)

@Composable
internal fun PlayerKeycap(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 24.dp)
            .background(Color.White.copy(alpha = 0.14f), KeycapShape)
            .border(1.dp, Color.White.copy(alpha = 0.32f), KeycapShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.nuvioTypeScale.labelSm.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
        )
    }
}
