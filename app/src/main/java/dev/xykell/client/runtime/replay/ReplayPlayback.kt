package dev.xykell.client.runtime.replay

/**
 * Time-indexed playback over a recorded [ReplayStore.Replay].
 *
 * **What this is.** A cursor: given a replay and an elapsed time, what frame was
 * showing then. That is enough to drive a review scrubber, a HUD echo, or a
 * diff between two takes, and it is deliberately *not* enough to drive input.
 *
 * **Why it stops there.** Feeding a recorded frame back into the game as input
 * is an automation path, and this project's boundary is that the observation
 * layer is read-only and replay must not quietly cross it. A caller that wants
 * input replay has to ask for it explicitly and go through the same Advanced /
 * Experimental gate every other automation uses. Making playback return input
 * ids by default would move that decision into a data structure.
 *
 * **Frame selection.** The frame in effect at time [t] is the last one at or
 * before it — a recording samples states, so between samples the last known
 * state is the truthful answer. Asking for a time past the end clamps to the
 * last frame rather than returning null, because "the recording ended" is not
 * an error; asking before the start returns the first frame.
 *
 * **Gaps are preserved.** Because a recording can contain segments separated by
 * a real silence (see [ReplayRecorder.MAX_GAP_MS]), [segmentAt] reports which
 * segment a time falls in rather than pretending the gap was continuous.
 */
class ReplayPlayback(replay: ReplayStore.Replay) {

    val frames: List<ReplayStore.Frame> = replay.frames
    val metadata: ReplayStore.Metadata = replay.metadata

    /** True when there are enough frames to scrub. */
    val playable: Boolean = replay.frames.size >= 2

    /** Timestamp of the first frame, or 0 when empty. */
    val startAtMs: Long = replay.frames.firstOrNull()?.timestampMs ?: 0L

    /** Elapsed span covered by the recording. */
    val durationMs: Long =
        if (replay.frames.size >= 2) replay.frames.last().timestampMs - startAtMs else 0L

    private val segments: List<IntRange> = buildSegments()

    /** Index of the frame showing at [elapsedMs], or -1 when there are none. */
    fun indexAt(elapsedMs: Long): Int {
        if (frames.isEmpty()) return -1
        if (elapsedMs <= 0) return 0
        val absolute = startAtMs + elapsedMs
        // Last frame at or before the requested absolute time.
        var lo = 0
        var hi = frames.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (frames[mid].timestampMs <= absolute) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found.coerceIn(0, frames.size - 1)
    }

    /** The frame showing at [elapsedMs], or null when the replay is empty. */
    fun frameAt(elapsedMs: Long): ReplayStore.Frame? {
        val i = indexAt(elapsedMs)
        return if (i < 0) null else frames[i]
    }

    /** Position at [elapsedMs], or null when the replay is empty. */
    fun positionAt(elapsedMs: Long): ReplayStore.Vector3? = frameAt(elapsedMs)?.position

    /**
     * Which recorded segment contains [elapsedMs], or null when the replay is
     * empty. Exposed so a scrubber can show the silence between takes instead
     * of drawing a straight line through samples that were never observed.
     */
    fun segmentAt(elapsedMs: Long): IntRange? {
        if (frames.isEmpty()) return null
        val i = indexAt(elapsedMs)
        return segments.firstOrNull { i in it }
    }

    /** Number of recorded segments; more than one means the stream broke. */
    val segmentTotal: Int get() = segments.size

    /** Elapsed time of a frame index, clamped to the recording. */
    fun elapsedOf(index: Int): Long {
        if (frames.isEmpty()) return 0
        val i = index.coerceIn(0, frames.size - 1)
        return frames[i].timestampMs - startAtMs
    }

    /**
     * Sample rate implied by the recording, or null when it cannot be computed
     * honestly. One frame is not a rate; saying "20 Hz" from a single sample
     * would be a number with no evidence behind it.
     */
    fun observedSampleHz(): Double? {
        if (frames.size < 2) return null
        val span = frames.last().timestampMs - frames.first().timestampMs
        if (span <= 0) return null
        val gaps = (frames.size - 1).toDouble()
        return (gaps * 1000.0) / span
    }

    /** Contiguous index ranges, split where consecutive samples exceed [gapMs]. */
    private fun buildSegments(): List<IntRange> {
        if (frames.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in 1 until frames.size) {
            if (frames[i].timestampMs - frames[i - 1].timestampMs > ReplayRecorder.MAX_GAP_MS) {
                out.add(start..(i - 1))
                start = i
            }
        }
        out.add(start..(frames.size - 1))
        return out
    }
}