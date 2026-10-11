package dev.xykell.client.runtime.replay

import dev.xykell.client.runtime.observation.ObservedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for replay recording.
 *
 * The property that carries the weight: a frame exists only for a coordinate
 * the observation layer actually produced. A recorder that filled gaps or
 * defaulted nulls would produce a replay that looks like a session and is not.
 */
class ReplayRecorderTest {

    private fun motion(
        atMs: Long,
        x: Double? = 1.0,
        y: Double? = 2.0,
        z: Double? = 3.0,
        yaw: Double? = 90.0,
    ) = ObservedState.Motion("e$atMs", atMs, x, y, z, yaw, 1.0, 1)

    @Test
    fun `observed samples become frames`() {
        val r = ReplayRecorder("take")
        assertTrue(r.onMotion(motion(0L)))
        assertTrue(r.onMotion(motion(50L, x = 2.0)))
        assertTrue(r.onMotion(motion(100L, x = 3.0)))
        val replay = r.stop()
        assertEquals(3, replay.frames.size)
        assertEquals(1.0, replay.frames[0].position.x, 1e-9)
        assertEquals(3.0, replay.frames[2].position.x, 1e-9)
        assertEquals(100L, replay.metadata.durationMs)
        assertEquals(0L, replay.metadata.recordedAtMs)
    }

    @Test
    fun `a sample with no observed coordinate is dropped, not defaulted`() {
        val r = ReplayRecorder("take")
        assertFalse(r.onMotion(motion(0L, x = null)))
        assertFalse(r.onMotion(motion(50L, y = null)))
        assertFalse(r.onMotion(motion(100L, z = null)))
        assertTrue(r.onMotion(motion(150L)))
        val replay = r.stop()
        assertEquals(1, replay.frames.size)
        assertEquals(150L, replay.frames[0].timestampMs)
    }

    @Test
    fun `a negative timestamp is refused`() {
        val r = ReplayRecorder("take")
        assertFalse(r.onMotion(motion(-1L)))
        assertEquals(0, r.frameCount)
    }

    @Test
    fun `an absent yaw leaves the unobserved axes at zero`() {
        // Pitch and roll were never observed, so they stay zero rather than
        // being invented.
        val r = ReplayRecorder("take")
        r.onMotion(motion(0L, yaw = null))
        r.onMotion(motion(50L, yaw = null))
        val f = r.stop().frames.first()
        assertEquals(0.0, f.rotation.x, 1e-9)
        assertEquals(0.0, f.rotation.y, 1e-9)
        assertEquals(0.0, f.rotation.z, 1e-9)
    }

    @Test
    fun `a long silence starts a new segment instead of bridging it`() {
        val r = ReplayRecorder("take")
        r.onMotion(motion(0L))
        r.onMotion(motion(100L))
        // Well past MAX_GAP_MS: the stream went quiet.
        r.onMotion(motion(100L + ReplayRecorder.MAX_GAP_MS + 1))
        r.onMotion(motion(200L + ReplayRecorder.MAX_GAP_MS + 1))
        val replay = r.stop()
        assertEquals(4, replay.frames.size)
        assertTrue("a broken stream must report two takes", replay.frames.isNotEmpty())
        assertEquals(2, ReplayPlayback(replay).segmentTotal)
    }

    @Test
    fun `the frame cap latches truncated and stops accepting samples`() {
        val r = ReplayRecorder("take", maxFrames = 3)
        assertTrue(r.onMotion(motion(0L)))
        assertTrue(r.onMotion(motion(50L)))
        assertTrue(r.onMotion(motion(100L)))
        assertFalse(r.onMotion(motion(150L)))
        assertTrue(r.truncated)
        assertFalse(r.recording)
        assertEquals(3, r.stop().frames.size)
    }

    @Test
    fun `inputs attach to the matching frame`() {
        val r = ReplayRecorder("take")
        r.onMotion(motion(0L))
        r.onMotion(motion(100L))
        r.noteInputs(100L, listOf("jump", "forward"))
        val replay = r.stop()
        assertEquals(setOf("jump", "forward"), replay.frames[1].inputs)
        assertTrue(replay.frames[0].inputs.isEmpty())
    }

    @Test
    fun `inputs for an unknown timestamp are ignored`() {
        val r = ReplayRecorder("take")
        r.onMotion(motion(0L))
        r.onMotion(motion(100L))
        r.noteInputs(999L, listOf("jump"))
        assertTrue(r.stop().frames.all { it.inputs.isEmpty() })
    }

    @Test
    fun `over-long input labels are dropped rather than stored`() {
        val r = ReplayRecorder("take")
        r.onMotion(motion(0L))
        r.onMotion(motion(100L))
        r.noteInputs(100L, listOf("ok", "x".repeat(ReplayStore.MAX_INPUT_LEN + 1), ""))
        assertEquals(setOf("ok"), r.stop().frames[1].inputs)
    }

    @Test
    fun `the input count is capped`() {
        val r = ReplayRecorder("take")
        r.onMotion(motion(0L))
        r.onMotion(motion(100L))
        r.noteInputs(
            100L,
            (0 until ReplayStore.MAX_INPUTS_PER_FRAME + 20).map { "k$it" },
        )
        assertEquals(ReplayStore.MAX_INPUTS_PER_FRAME, r.stop().frames[1].inputs.size)
    }

    @Test
    fun `stopping twice yields the same replay`() {
        val r = ReplayRecorder("take")
        r.onMotion(motion(0L))
        r.onMotion(motion(100L))
        val first = r.stop()
        val second = r.stop()
        assertEquals(first.frames.size, second.frames.size)
        assertFalse(r.recording)
    }

    @Test
    fun `samples after stop are refused`() {
        val r = ReplayRecorder("take")
        r.onMotion(motion(0L))
        r.onMotion(motion(100L))
        r.stop()
        assertFalse(r.onMotion(motion(150L)))
    }

    @Test
    fun `discard throws the recording away`() {
        val r = ReplayRecorder("take")
        r.onMotion(motion(0L))
        r.onMotion(motion(100L))
        r.discard()
        assertTrue(r.stop().frames.isEmpty())
    }

    @Test
    fun `a recording with fewer than two frames is not playable`() {
        val r = ReplayRecorder("take")
        r.onMotion(motion(0L))
        assertFalse(r.stop().playable)
    }

    @Test
    fun `an empty recorder reports zero duration and no segments`() {
        val replay = ReplayRecorder("take").stop()
        assertEquals(0L, replay.metadata.durationMs)
        assertTrue(replay.frames.isEmpty())
        assertEquals(0, ReplayPlayback(replay).segmentTotal)
    }

    @Test
    fun `an out-of-range frame cap is rejected at construction`() {
        var threw = false
        try {
            ReplayRecorder("take", maxFrames = 0)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}