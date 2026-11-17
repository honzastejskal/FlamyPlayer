package io.github.flamy69.flamyplayer.video

/**
 * Fast forward and rewind with the buttons of a remote control, the way Kodi does it: every
 * press of fast forward goes one step up the ladder 2× → 4× → 8× → 16× → 32×, every press of
 * rewind one step down. Pressing the other button first slows down, through normal playback,
 * and then speeds up in the other direction.
 *
 * Only 2× forward is real playback at double speed. Faster, and backwards, the player jumps
 * from one key frame to the next a few times a second, without sound; [advance] tells where to.
 */
class TrickPlay {

    /** The current speed: [NORMAL], a step of [SPEEDS], or the negative of one for rewinding. */
    var speed: Int = NORMAL
        private set

    /** Whether anything else than normal playback is going on. */
    val isActive: Boolean get() = speed != NORMAL

    /** Whether the player plays at [speed] itself instead of jumping. */
    val isRealPlayback: Boolean get() = speed == NORMAL || speed == SPEEDS.first()

    /** One step faster forward, or slower backward. Returns the new speed. */
    fun fastForward(): Int {
        speed = LADDER[(LADDER.indexOf(speed) + 1).coerceAtMost(LADDER.lastIndex)]
        return speed
    }

    /** One step faster backward, or slower forward. Returns the new speed. */
    fun rewind(): Int {
        speed = LADDER[(LADDER.indexOf(speed) - 1).coerceAtLeast(0)]
        return speed
    }

    /** Back to normal playback. */
    fun stop() {
        speed = NORMAL
    }

    /**
     * Where a jump goes after [elapsedMs] of real time at the current speed, starting from
     * [positionMs]. It never leaves the video, and stops [END_MARGIN_MS] before its end, so
     * that the last seconds play normally and the film ends the usual way. A [durationMs] of
     * zero or less means the length is not known, and only the start limits the jump.
     */
    fun advance(positionMs: Long, elapsedMs: Long, durationMs: Long): Long {
        val target = positionMs + speed * elapsedMs
        // Already in the last seconds: stay, rather than jump back.
        return target.coerceIn(0, maxOf(lastPosition(durationMs), positionMs))
    }

    /** Whether a jump to [positionMs] has hit the start or the end, so that it is over. */
    fun hasReachedLimit(positionMs: Long, durationMs: Long): Boolean = when {
        speed < 0 -> positionMs <= 0
        speed > 0 -> positionMs >= lastPosition(durationMs)
        else -> false
    }

    private fun lastPosition(durationMs: Long): Long =
        if (durationMs > 0) (durationMs - END_MARGIN_MS).coerceAtLeast(0) else Long.MAX_VALUE

    companion object {
        const val NORMAL = 1

        /** How far from the end fast forward stops. */
        const val END_MARGIN_MS = 2_000L

        /** The steps of the ladder, in each direction. */
        val SPEEDS = listOf(2, 4, 8, 16, 32)

        /** All speeds from the fastest rewind to the fastest fast forward. */
        private val LADDER: List<Int> = SPEEDS.reversed().map { -it } + NORMAL + SPEEDS
    }
}
