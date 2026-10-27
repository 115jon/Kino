package com.nuvio.app.features.player.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoFrameQueueTest {
    @Test
    fun `frames are presented oldest first`() {
        val queue = VideoFrameQueue<Int>(capacity = 2)
        queue.push(1, redraw = false, presenting = true)
        queue.push(2, redraw = false, presenting = true)
        assertEquals(1, queue.take())
        assertEquals(2, queue.take())
        assertNull(queue.take())
    }

    @Test
    fun `overflow drops the oldest frame and reports it`() {
        val queue = VideoFrameQueue<Int>(capacity = 2)
        queue.push(1, redraw = false, presenting = true)
        queue.push(2, redraw = false, presenting = true)
        assertEquals(1, queue.push(3, redraw = false, presenting = true))
        assertEquals(2, queue.take())
        assertEquals(3, queue.take())
    }

    @Test
    fun `a redraw replaces the newest pending frame`() {
        val queue = VideoFrameQueue<Int>(capacity = 2)
        queue.push(1, redraw = false, presenting = true)
        queue.push(2, redraw = false, presenting = true)
        assertEquals(0, queue.push(3, redraw = true, presenting = true))
        assertEquals(1, queue.take())
        assertEquals(3, queue.take())
    }

    @Test
    fun `a hidden surface keeps only the newest frame`() {
        val queue = VideoFrameQueue<Int>(capacity = 2)
        queue.push(1, redraw = false, presenting = false)
        queue.push(2, redraw = false, presenting = false)
        assertEquals(1, queue.size)
        assertEquals(2, queue.take())
    }

    @Test
    fun `keeping the newest drops older pending frames`() {
        val queue = VideoFrameQueue<Int>(capacity = 2)
        queue.push(1, redraw = false, presenting = true)
        queue.push(2, redraw = false, presenting = true)
        queue.keepNewest()
        assertEquals(false, 1 in queue)
        assertEquals(2, queue.take())
    }
}
