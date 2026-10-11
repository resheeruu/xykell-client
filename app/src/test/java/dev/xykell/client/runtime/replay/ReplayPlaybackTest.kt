package dev.xykell.client.runtime.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for replay playback.
 *
 * Two properties matter beyond ordinary lookup: playback clamps at the ends
 * instead of returning null, because "the recording ended" is not an error; and
 * a sample rate is reported only when one can honestly be computed, because one
 * frame is not a rate.
 */
class ReplayPlaybackTest {

    private fun replay(vararg ts: Long): ReplayStore.Replay {
        val frames = ts.mapIndexed { i, t ->
            ReplayStore.Frame(
                ReplayStore.Vector3(i.toDouble(), 0.0, 0.0),
                ReplayStore.Vector3(0.0, 0.0, 0.0),
                emptySet(),
                t,
            )
        }
        return ReplayStore.Replay(
            ReplayStore.Metadata(1, frames.size.toLong(), ts.firstOrNull() ?: 0L, 0L),
            frames,
        )
    }

    @Test
    fun `the frame showing at a time is the last one at or before it`() {
        val p = ReplayPlayback(replay(1000L, 1100L, 1200L, 1300L))
        assertEquals(0, p.indexAt(0))
        assertEquals(0, p.indexAt(99))
        assertEquals(1, p.indexAt(100))
        assertEquals(1, p.indexAt(199))
        assertEquals(3, p.indexAt(1000))
    }

    @Test
    fun `times before the start and after the end clamp`() {
        val p = ReplayPlayback(replay(1000L, 1100L, 1200L))
        assertEquals(0, p.indexAt(-5000))
        assertEquals(2, p.indexAt(999_999))
    }

    @Test
    fun `an empty replay has no frame at any time`() {
        val p = ReplayPlayback(replay())
        assertEquals(-1, p.indexAt(0))
        assertNull(p.frameAt(0))
        assertNull(p.positionAt(0))
        assertNull(p.segmentAt(0))
        assertFalse(p.playable)
    }

    @Test
    fun `a single frame is not playable but still answers`() {
        val p = ReplayPlayback(replay(1000L))
        assertFalse(p.playable)
        assertEquals(0, p.indexAt(5000))
        assertNotNull(p.frameAt(0))
    }

    @Test
    fun `duration and start derive from the frames`() {
        val p = ReplayPlayback(replay(5000L, 5500L, 6000L))
        assertEquals(5000L, p.startAtMs)
        assertEquals(1000L, p.durationMs)
        assertTrue(p.playable)
    }

    @Test
    fun `elapsedOf inverts indexAt and clamps`() {
        val p = ReplayPlayback(replay(1000L, 1100L, 1200L))
        assertEquals(0L, p.elapsedOf(0))
        assertEquals(200L, p.elapsedOf(2))
        assertEquals(0L, p.elapsedOf(-5))
        assertEquals(200L, p.elapsedOf(999))
    }

    @Test
    fun `positionAt returns the last coordinate observed at or before the time`() {
        val p = ReplayPlayback(replay(0L, 100L, 200L))
        // Frames carry x = their index (0, 1, 2).
        assertEquals(0.0, p.positionAt(0)!!.x, 1e-9)
        assertEquals(0.0, p.positionAt(99)!!.x, 1e-9)
        assertEquals(1.0, p.positionAt(100)!!.x, 1e-9)
        // 150ms is still inside the state observed at 100ms, not a guess
        // halfway to the next one.
        assertEquals(1.0, p.positionAt(150)!!.x, 1e-9)
        assertEquals(2.0, p.positionAt(200)!!.x, 1e-9)
    }

    @Test
    fun `a gap in the recording reports more than one segment`() {
        val far = ReplayRecorder.MAX_GAP_MS + 1
        val p = ReplayPlayback(replay(0L, 100L, 100L + far, 200L + far))
        assertEquals(2, p.segmentTotal)
        assertNotNull(p.segmentAt(50))
        assertNotNull(p.segmentAt(100L + far))
        // The two takes are distinct ranges, so a scrubber can show the silence.
        val s0 = p.segmentAt(50)!!
        val s1 = p.segmentAt(100L + far)!!
        assertFalse(s0 == s1)
    }

    @Test
    fun `a contiguous recording is one segment`() {
        val p = ReplayPlayback(replay(0L, 100L, 200L, 300L))
        assertEquals(1, p.segmentTotal)
    }

    @Test
    fun `sample rate is reported only when it can be computed`() {
        // One frame is not a rate; a number here would have no evidence behind it.
        assertNull(ReplayPlayback(replay(0L)).observedSampleHz())
        // Every frame at the same instant is not a rate either.
        assertNull(ReplayPlayback(replay(0L, 0L, 0L)).observedSampleHz())

        val p = ReplayPlayback(replay(0L, 100L, 200L, 300L))
        assertEquals(10.0, p.observedSampleHz()!!, 1e-6)
    }

    @Test
    fun `playback over an empty recording reports zero span`() {
        val p = ReplayPlayback(replay())
        assertEquals(0L, p.startAtMs)
        assertEquals(0L, p.durationMs)
    }

    @Test
    fun `playback survives a recording that is not playable`() {
        val p = ReplayPlayback(replay(7L))
        assertEquals(7L, p.startAtMs)
        assertEquals(0L, p.durationMs)
        assertEquals(1, p.segmentTotal)
    }
}