package dev.xykell.client.runtime.replay

import dev.xykell.client.runtime.observation.ObservedState

/**
 * Accumulates a [ReplayStore.Replay] from the read-only observation stream.
 *
 * **Where the data comes from.** Only [ObservedState]. A recorder started with
 * no observation attached records nothing and reports nothing, which is the
 * point: a replay is a record of what was observed, so a replay cannot contain
 * a position nobody observed. Nothing here reads the game, contacts a server,
 * or synthesises a sample to fill a gap.
 *
 * **Sampling.** Frames are recorded on observed movement, not on a timer, so
 * the frame count tracks what was actually seen. A gap longer than
 * [MAX_GAP_MS] between observations means the stream went quiet; rather than
 * bridge it with interpolated points that were never observed, the recorder
 * closes the current run and starts a new one at the next sample. The replay
 * then reports two segments instead of pretending continuity.
 *
 * **Bounded.** [ReplayStore.MAX_FRAMES] is a hard stop: the recorder latches
 * [truncated] and stops accepting samples rather than growing without limit.
 * Inputs are trimmed per frame to the store's cap.
 */
class ReplayRecorder(
    /** Caller-chosen recording name. Checked with [ReplayStore.isSafeName]
     *  before it is ever used to build a path. */
    val name: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFrames: Int = ReplayStore.MAX_FRAMES,
) {

    init {
        require(maxFrames in 1..ReplayStore.MAX_FRAMES) { "maxFrames out of range" }
    }

    private val segments = ArrayList<List<ReplayStore.Frame>>()
    private var current = ArrayList<ReplayStore.Frame>()
    private var startedAtMs = 0L
    private var lastAtMs = Long.MIN_VALUE
    private var closed = false
    private var lastReplay: ReplayStore.Replay? = null

    val frameCount: Int get() = synchronized(this) { segments.sumOf { it.size } + current.size }

    val segmentCount: Int get() = synchronized(this) { if (current.isEmpty()) segments.size else segments.size + 1 }

    /** True when the frame cap stopped the recording early. */
    @Volatile
    var truncated: Boolean = false
        private set

    @Volatile
    var recording: Boolean = true
        private set

    /**
     * Records one observed movement sample. Returns false when the sample was
     * not recorded and the caller should not assume otherwise.
     *
     * A sample carrying no usable coordinate is dropped rather than recorded
     * with nulls — a frame with a hole in it is worse than a missing frame.
     */
    fun onMotion(motion: ObservedState.Motion): Boolean = synchronized(this) {
        if (!recording || closed) return false
        if (truncated) return false
        val x = motion.x ?: return false
        val y = motion.y ?: return false
        val z = motion.z ?: return false
        val at = motion.atMs
        if (at < 0) return false

        // A long silence ends the segment rather than being bridged.
        if (lastAtMs != Long.MIN_VALUE && at - lastAtMs > MAX_GAP_MS) {
            commitCurrent()
        }
        if (current.size >= maxFrames) {
            truncated = true
            recording = false
            commitCurrent()
            return false
        }

        val yaw = motion.yawDegrees ?: 0.0
        current.add(
            ReplayStore.Frame(
                position = ReplayStore.Vector3(x, y, z),
                // Only yaw was observed; pitch and roll are axes this layer
                // never received, so they stay zero rather than being guessed.
                rotation = ReplayStore.Vector3(yaw, 0.0, 0.0),
                inputs = emptySet(),
                timestampMs = at,
            ),
        )
        if (startedAtMs == 0L) startedAtMs = at
        lastAtMs = at
        true
    }

    /** Attaches the input labels observed alongside a frame. Bounded by the store. */
    fun noteInputs(atMs: Long, labels: Collection<String>) {
        synchronized(this) {
            if (!recording) return
            val idx = current.indexOfLast { it.timestampMs == atMs }
            if (idx < 0) return
            val trimmed = labels.asSequence()
                .filter { it.isNotEmpty() && it.length <= ReplayStore.MAX_INPUT_LEN }
                .take(ReplayStore.MAX_INPUTS_PER_FRAME)
                .toSet()
            current[idx] = current[idx].copy(inputs = trimmed)
        }
    }

    /**
     * Stops recording and returns what was captured. Idempotent: a second call
     * returns the same replay rather than an empty one, because a caller that
     * stops twice (a UI teardown racing an explicit stop, say) must not get a
     * silently empty result and conclude the recording never captured anything.
     */
    fun stop(): ReplayStore.Replay = synchronized(this) {
        lastReplay?.let { return it }
        recording = false
        closed = true
        commitCurrent()

        val frames = ArrayList<ReplayStore.Frame>()
        // Segments are concatenated with their gaps intact: playback walks
        // timestamps, so a gap is visible as a pause rather than smoothed away.
        for (seg in segments) frames.addAll(seg)
        segments.clear()

        val first = frames.firstOrNull()?.timestampMs
        val last = frames.lastOrNull()?.timestampMs
        val replay = ReplayStore.Replay(
            metadata = ReplayStore.Metadata(
                version = ReplayStore.SCHEMA_VERSION,
                frameCount = frames.size.toLong(),
                // A recording that captured nothing records when it was stopped,
                // not a start time that never existed.
                recordedAtMs = first ?: clock(),
                durationMs = if (frames.size >= 2) (last!! - first!!) else 0L,
            ),
            frames = frames,
        )
        lastReplay = replay
        return replay
    }

    /**
     * Moves the in-progress frames into the segment list. A single frame is
     * kept: it was observed, and dropping it would discard real data. Whether
     * one frame is enough to *play back* is [ReplayStore.Replay.playable]'s
     * question, not the recorder's.
     */
    private fun commitCurrent() {
        if (current.isNotEmpty()) segments.add(current)
        current = ArrayList()
    }

    /** Abandons the recording without producing a replay. */
    fun discard() = synchronized(this) {
        recording = false
        closed = true
        lastReplay = null
        segments.clear()
        current = ArrayList()
    }

    companion object {
        /**
         * Longest silence that still counts as one continuous take. Beyond this
         * the stream is treated as broken and a new segment begins, so playback
         * shows a real pause instead of a straight line through missing data.
         */
        const val MAX_GAP_MS = 5_000L
    }
}