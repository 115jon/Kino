package com.nuvio.app.features.player

import androidx.compose.runtime.setValue
import java.awt.*
import java.awt.event.*
import javax.swing.*
import javax.swing.plaf.basic.BasicSliderUI

internal class DarkButton(
    text: String,
    private val defaultBg: java.awt.Color,
    var accentColor: java.awt.Color? = null
) : JButton(text) {
    private var isHovered = false
    private var isPressed = false

    init {
        isFocusable = false
        foreground = java.awt.Color.WHITE
        font = Font("Segoe UI", Font.PLAIN, 13)
        isContentAreaFilled = false
        isBorderPainted = false
        border = BorderFactory.createEmptyBorder(6, 12, 6, 12)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                isHovered = true
                repaint()
            }
            override fun mouseExited(e: MouseEvent) {
                isHovered = false
                isPressed = false
                repaint()
            }
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    isPressed = true
                    repaint()
                }
            }
            override fun mouseReleased(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    isPressed = false
                    repaint()
                }
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val bg = when {
            !isEnabled -> defaultBg.darker()
            isPressed -> accentColor?.darker() ?: defaultBg.darker()
            isHovered -> accentColor?.brighter() ?: defaultBg.brighter()
            else -> accentColor ?: defaultBg
        }
        g2.color = bg
        g2.fillRoundRect(0, 0, width, height, 8, 8)

        // Subtle outline border
        g2.color = java.awt.Color(255, 255, 255, 20)
        g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)

        g2.dispose()
        super.paintComponent(g)
    }
}

internal class ModernSliderUI(
    slider: JSlider,
    private val accentColor: java.awt.Color
) : javax.swing.plaf.basic.BasicSliderUI(slider) {
    override fun paintTrack(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val trackHeight = 6
        val y = trackRect.y + (trackRect.height - trackHeight) / 2

        // Draw track background
        g2.color = slider.background
        g2.fillRoundRect(trackRect.x, y, trackRect.width, trackHeight, 3, 3)

        // Draw track fill (progress)
        val valueX = thumbRect.x + thumbRect.width / 2
        g2.color = if (slider.isEnabled) accentColor else java.awt.Color(100, 100, 100)
        g2.fillRoundRect(trackRect.x, y, valueX - trackRect.x, trackHeight, 3, 3)

        g2.dispose()
    }

    override fun paintThumb(g: Graphics) {
        if (!slider.isEnabled) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val size = 12
        val x = thumbRect.x + (thumbRect.width - size) / 2
        val y = thumbRect.y + (thumbRect.height - size) / 2

        // Draw white thumb circle
        g2.color = java.awt.Color.WHITE
        g2.fillOval(x, y, size, size)

        // Outer subtle border
        g2.color = java.awt.Color(0, 0, 0, 100)
        g2.drawOval(x, y, size, size)

        g2.dispose()
    }

    override fun paintFocus(g: Graphics) {}

    override fun getThumbSize(): Dimension {
        return Dimension(16, 16)
    }

    override fun createTrackListener(slider: JSlider): TrackListener {
        return object : TrackListener() {
            override fun mousePressed(e: MouseEvent) {
                if (slider.isEnabled) {
                    val value = valueForXPosition(e.x)
                    slider.setValue(value)
                    super.mousePressed(e)
                }
            }
        }
    }
}
