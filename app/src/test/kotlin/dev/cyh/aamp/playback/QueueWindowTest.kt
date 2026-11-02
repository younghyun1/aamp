package dev.cyh.aamp.playback

import dev.cyh.aamp.playback.QueueWindow.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueWindowTest {
    @Test
    fun `short queues go to the player whole`() {
        assertEquals(Span(0, 12), QueueWindow.span(12, 5))
        assertEquals(Span(0, QueueWindow.MAX_WHOLE), QueueWindow.span(QueueWindow.MAX_WHOLE, 399))
    }

    @Test
    fun `long queues start with a window around the first song`() {
        // The whole library shuffled starts at its first song.
        assertEquals(Span(0, QueueWindow.WINDOW), QueueWindow.span(7600, 0))
        // A song picked in the middle of the Songs list keeps a few before it for previous.
        assertEquals(Span(5000 - QueueWindow.BEFORE, 5000 - QueueWindow.BEFORE + QueueWindow.WINDOW), QueueWindow.span(7600, 5000))
        // Near the end the window stops at the last song.
        val end = QueueWindow.span(7600, 7599)
        assertEquals(7600, end.last)
        assertTrue(7599 in end.first until end.last)
    }
}
