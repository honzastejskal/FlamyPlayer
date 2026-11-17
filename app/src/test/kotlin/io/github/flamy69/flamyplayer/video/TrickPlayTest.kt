package io.github.flamy69.flamyplayer.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrickPlayTest {

    @Test
    fun climbsTheLadderAndStaysAtTheTop() {
        val trick = TrickPlay()
        assertEquals(listOf(2, 4, 8, 16, 32, 32), List(6) { trick.fastForward() })

        val back = TrickPlay()
        assertEquals(listOf(-2, -4, -8, -16, -32, -32), List(6) { back.rewind() })
    }

    @Test
    fun theOtherButtonStepsDownThroughNormalPlayback() {
        val trick = TrickPlay()
        repeat(3) { trick.fastForward() }
        assertEquals(8, trick.speed)
        assertEquals(listOf(4, 2, TrickPlay.NORMAL, -2, -4), List(5) { trick.rewind() })
        assertEquals(listOf(-2, TrickPlay.NORMAL, 2), List(3) { trick.fastForward() })
    }

    @Test
    fun stopReturnsToNormalPlayback() {
        val trick = TrickPlay()
        trick.rewind()
        assertTrue(trick.isActive)
        trick.stop()
        assertFalse(trick.isActive)
        assertEquals(TrickPlay.NORMAL, trick.speed)
        // And the ladder starts again from the bottom.
        assertEquals(2, trick.fastForward())
    }

    @Test
    fun onlyDoubleSpeedForwardIsRealPlayback() {
        val trick = TrickPlay()
        assertTrue(trick.isRealPlayback)
        trick.fastForward()
        assertTrue(trick.isRealPlayback)
        trick.fastForward()
        assertFalse(trick.isRealPlayback)
        trick.stop()
        trick.rewind()
        assertFalse(trick.isRealPlayback)
    }

    @Test
    fun jumpsBySpeedTimesElapsedTime() {
        val trick = TrickPlay()
        repeat(3) { trick.fastForward() }
        assertEquals(60_000L + 8 * 250, trick.advance(60_000, 250, 3_600_000))
        repeat(4) { trick.rewind() }
        assertEquals(-2, trick.speed)
        assertEquals(60_000L - 2 * 500, trick.advance(60_000, 500, 3_600_000))
    }

    @Test
    fun stopsAtTheStart() {
        val trick = TrickPlay()
        repeat(5) { trick.rewind() }
        val target = trick.advance(3_000, 250, 3_600_000)
        assertEquals(0L, target)
        assertTrue(trick.hasReachedLimit(target, 3_600_000))
        assertFalse(trick.hasReachedLimit(1, 3_600_000))
    }

    @Test
    fun stopsShortlyBeforeTheEnd() {
        val trick = TrickPlay()
        repeat(5) { trick.fastForward() }
        val duration = 600_000L
        val last = duration - TrickPlay.END_MARGIN_MS
        val target = trick.advance(duration - 5_000, 250, duration)
        assertEquals(last, target)
        assertTrue(trick.hasReachedLimit(target, duration))
        assertFalse(trick.hasReachedLimit(last - 1, duration))
        // Already in the last seconds: it does not jump back.
        assertEquals(duration - 1_000, trick.advance(duration - 1_000, 250, duration))
    }

    @Test
    fun withoutAKnownLengthOnlyTheStartLimits() {
        val trick = TrickPlay()
        repeat(5) { trick.fastForward() }
        assertEquals(10_000L + 32 * 1_000, trick.advance(10_000, 1_000, 0))
        assertFalse(trick.hasReachedLimit(Long.MAX_VALUE / 2, 0))
    }
}
