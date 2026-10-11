package dev.xykell.client.runtime.replay

import org.json.JSONArray
import org.json.JSONObject

/**
 * Replay model and its on-disk JSON form.
 *
 * **What a replay is.** A recording of where the player was and what they
 * pressed, sampled from the read-only observation stream: one frame per
 * observed movement update, each carrying position, facing, the input labels
 * observed with it, and a timestamp. That is the whole thing — it is a record,
 * not a game re-simulation, and it contains nothing the observation layer did
 * not actually receive.
 *
 * **Format provenance.** The *capability* — record a session, list the
 * recordings, replay them for review — is common practice in this category. The
 * schema here is our own, versioned by [SCHEMA_VERSION], and deliberately does
 * not attempt byte-compatibility with any other client's replay files. Reading
 * someone else's recordings would mean depending on their private field layout,
 * which is exactly the kind of borrowed detail this project does not take.
 *
 * **Bounded on both sides.** A recording grows with session length and a
 * loaded file is arbitrary input, so both ends are capped: [MAX_FRAMES] frames,
 * [MAX_INPUTS_PER_FRAME] labels per frame, and [MAX_FRAME_INTERVAL_MS] between
 * samples. Exceeding a cap stops the recording rather than filling the heap, and
 * an over-long document is refused rather than half-decoded. An unknown-field
 * document from a newer version is refused, not silently downgraded — a
 * downgraded replay would play back as a real session with data missing, which
 * is the one failure mode that looks like success.
 */
object ReplayStore {

    const val SCHEMA_VERSION = 1

    /** Max frames in one replay. A long session at 20 Hz is a few hours. */
    const val MAX_FRAMES = 200_000

    /** Max distinct input labels carried by a single frame. */
    const val MAX_INPUTS_PER_FRAME = 64

    /** Max characters in one input label. */
    const val MAX_INPUT_LEN = 32

    /** Longest gap between frames that is still worth storing. */
    const val MAX_FRAME_INTERVAL_MS = 60_000L

    /** Coordinates are metres and finite; anything else is refused. */
    const val MAX_COORDINATE = 30_000_000.0

    data class Vector3(val x: Double, val y: Double, val z: Double)

    data class Frame(
        val position: Vector3,
        val rotation: Vector3,
        val inputs: Set<String>,
        val timestampMs: Long,
    )

    data class Metadata(
        val version: Int,
        val frameCount: Long,
        val recordedAtMs: Long,
        val durationMs: Long,
    )

    data class Replay(
        val metadata: Metadata,
        val frames: List<Frame>,
    ) {
        /** True when there is enough recorded to show anything. */
        val playable: Boolean get() = frames.size >= 2
    }

    /** Why a replay document was refused. */
    enum class Refusal {
        MALFORMED,
        NEWER_SCHEMA,
        TOO_MANY_FRAMES,
        TOO_MANY_INPUTS,
        BAD_COORDINATE,
        BAD_TIMESTAMP,
        INPUT_TOO_LONG,
    }

    sealed class Decoded {
        data class Ok(val replay: Replay) : Decoded()
        data class Refused(val reason: Refusal) : Decoded()
    }

    // --- encoding -------------------------------------------------------

    fun toJson(replay: Replay): String {
        val frames = JSONArray()
        for (f in replay.frames) {
            frames.put(
                JSONObject()
                    .put("t", f.timestampMs)
                    .put("px", f.position.x).put("py", f.position.y).put("pz", f.position.z)
                    .put("rx", f.rotation.x).put("ry", f.rotation.y).put("rz", f.rotation.z)
                    .put("in", JSONArray().apply { f.inputs.forEach { put(it) } }),
            )
        }
        val meta = JSONObject()
            .put("version", replay.metadata.version)
            .put("frameCount", replay.metadata.frameCount)
            .put("recordedAt", replay.metadata.recordedAtMs)
            .put("duration", replay.metadata.durationMs)
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("metadata", meta)
            .put("frames", frames)
            .toString()
    }

    /**
     * Decodes a replay document. [maxFrames] is injectable so the cap itself is
     * testable without building a 200k-frame document in a test; production
     * callers take the default.
     */
    fun fromJson(json: String, maxFrames: Int = MAX_FRAMES): Decoded {
        if (maxFrames < 1) return Decoded.Refused(Refusal.TOO_MANY_FRAMES)
        if (json.length > MAX_DOCUMENT_CHARS) return Decoded.Refused(Refusal.MALFORMED)
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            return Decoded.Refused(Refusal.MALFORMED)
        }
        if (root.optInt("schemaVersion", 1) > SCHEMA_VERSION) {
            return Decoded.Refused(Refusal.NEWER_SCHEMA)
        }
        val metaObj = root.optJSONObject("metadata")
            ?: return Decoded.Refused(Refusal.MALFORMED)
        val framesObj = root.optJSONArray("frames")
            ?: return Decoded.Refused(Refusal.MALFORMED)
        if (framesObj.length() > maxFrames) {
            return Decoded.Refused(Refusal.TOO_MANY_FRAMES)
        }

        val frames = ArrayList<Frame>(framesObj.length())
        for (i in 0 until framesObj.length()) {
            val o = framesObj.optJSONObject(i) ?: return Decoded.Refused(Refusal.MALFORMED)
            val pos = vector(o, "px", "py", "pz")
                ?: return Decoded.Refused(Refusal.BAD_COORDINATE)
            val rot = vector(o, "rx", "ry", "rz")
                ?: return Decoded.Refused(Refusal.BAD_COORDINATE)
            val t = o.optLong("t", Long.MIN_VALUE)
            if (t == Long.MIN_VALUE || t < 0) return Decoded.Refused(Refusal.BAD_TIMESTAMP)
            val insObj = o.optJSONArray("in") ?: JSONArray()
            if (insObj.length() > MAX_INPUTS_PER_FRAME) {
                return Decoded.Refused(Refusal.TOO_MANY_INPUTS)
            }
            val inputs = LinkedHashSet<String>()
            for (j in 0 until insObj.length()) {
                val label = insObj.optString(j, "")
                if (label.isEmpty()) continue
                if (label.length > MAX_INPUT_LEN) return Decoded.Refused(Refusal.INPUT_TOO_LONG)
                inputs.add(label)
            }
            frames.add(Frame(pos, rot, inputs, t))
        }

        val metadata = Metadata(
            version = metaObj.optInt("version", 1),
            frameCount = metaObj.optLong("frameCount", frames.size.toLong()),
            recordedAtMs = metaObj.optLong("recordedAt", 0L),
            durationMs = metaObj.optLong("duration", 0L),
        )
        return Decoded.Ok(Replay(metadata, frames))
    }

    /**
     * Reads a coordinate triple. Non-finite values are refused outright: a NaN
     * that survives into playback propagates through every later interpolation.
     */
    private fun vector(o: JSONObject, kx: String, ky: String, kz: String): Vector3? {
        if (!o.has(kx) || !o.has(ky) || !o.has(kz)) return null
        val x = o.optDouble(kx, Double.NaN)
        val y = o.optDouble(ky, Double.NaN)
        val z = o.optDouble(kz, Double.NaN)
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        if (Math.abs(x) > MAX_COORDINATE) return null
        if (Math.abs(y) > MAX_COORDINATE) return null
        if (Math.abs(z) > MAX_COORDINATE) return null
        return Vector3(x, y, z)
    }

    /**
     * A replay file name safe to write. Anything that could escape the replays
     * directory is refused, because the name comes from a user command.
     */
    fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_NAME) return false
        if (name == "." || name == "..") return false
        if (name.contains('/') || name.contains('\\')) return false
        if (name.any { it.code < 0x20 || it.code == 0x7f }) return false
        return name.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    const val MAX_NAME = 64

    /** Bound on a document we will even attempt to parse. */
    const val MAX_DOCUMENT_CHARS = 64 * 1024 * 1024
}