package dev.xykell.client.runtime.replay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host JVM tests for the replay model and its JSON form.
 *
 * The properties that matter: a round trip is lossless, a malformed or hostile
 * document is refused rather than half-decoded, and a newer schema is refused
 * instead of silently downgraded into a replay that looks complete.
 */
class ReplayStoreTest {

    private fun replay(vararg frames: ReplayStore.Frame) = ReplayStore.Replay(
        ReplayStore.Metadata(1, frames.size.toLong(), 1000L, 0L),
        frames.toList(),
    )

    private fun frame(t: Long, x: Double = 1.0, inputs: Set<String> = emptySet()) =
        ReplayStore.Frame(
            ReplayStore.Vector3(x, 2.0, 3.0),
            ReplayStore.Vector3(90.0, 0.0, 0.0),
            inputs,
            t,
        )

    @Test
    fun `a round trip is lossless`() {
        val r = replay(frame(0L, 1.5), frame(100L, 2.5, setOf("jump")), frame(200L, 3.5))
        val decoded = ReplayStore.fromJson(ReplayStore.toJson(r))
        assertTrue(decoded is ReplayStore.Decoded.Ok)
        val back = (decoded as ReplayStore.Decoded.Ok).replay
        assertEquals(3, back.frames.size)
        assertEquals(1.5, back.frames[0].position.x, 1e-9)
        assertEquals(setOf("jump"), back.frames[1].inputs)
        assertEquals(200L, back.frames[2].timestampMs)
    }

    @Test
    fun `an empty replay round-trips and is not playable`() {
        val r = replay()
        val back = (ReplayStore.fromJson(ReplayStore.toJson(r)) as ReplayStore.Decoded.Ok).replay
        assertTrue(back.frames.isEmpty())
        assertTrue(!back.playable)
    }

    @Test
    fun `a single frame is not playable`() {
        val back = (ReplayStore.fromJson(ReplayStore.toJson(replay(frame(0L)))) as ReplayStore.Decoded.Ok).replay
        assertTrue(!back.playable)
    }

    @Test
    fun `malformed json is refused`() {
        assertEquals(
            ReplayStore.Refusal.MALFORMED,
            (ReplayStore.fromJson("not json") as ReplayStore.Decoded.Refused).reason,
        )
        assertEquals(
            ReplayStore.Refusal.MALFORMED,
            (ReplayStore.fromJson("{}") as ReplayStore.Decoded.Refused).reason,
        )
        assertEquals(
            ReplayStore.Refusal.MALFORMED,
            (ReplayStore.fromJson("""{"schemaVersion":1,"metadata":{}}""") as ReplayStore.Decoded.Refused).reason,
        )
    }

    @Test
    fun `a newer schema is refused rather than downgraded`() {
        // A downgraded replay would play back as a real session with data
        // missing, which is the one failure that looks like success.
        val doc = JSONObject()
            .put("schemaVersion", ReplayStore.SCHEMA_VERSION + 1)
            .put("metadata", JSONObject())
            .put("frames", org.json.JSONArray())
        assertEquals(
            ReplayStore.Refusal.NEWER_SCHEMA,
            (ReplayStore.fromJson(doc.toString()) as ReplayStore.Decoded.Refused).reason,
        )
    }

    @Test
    fun `a non-finite coordinate is refused`() {
        // NaN survives into playback and propagates through every interpolation.
        val doc = """{"schemaVersion":1,"metadata":{},"frames":[
            {"t":0,"px":"NaN","py":0,"pz":0,"rx":0,"ry":0,"rz":0}]}"""
        assertEquals(
            ReplayStore.Refusal.BAD_COORDINATE,
            (ReplayStore.fromJson(doc) as ReplayStore.Decoded.Refused).reason,
        )
    }

    @Test
    fun `an out-of-range coordinate is refused`() {
        val doc = """{"schemaVersion":1,"metadata":{},"frames":[
            {"t":0,"px":1e12,"py":0,"pz":0,"rx":0,"ry":0,"rz":0}]}"""
        assertEquals(
            ReplayStore.Refusal.BAD_COORDINATE,
            (ReplayStore.fromJson(doc) as ReplayStore.Decoded.Refused).reason,
        )
    }

    @Test
    fun `a missing coordinate is refused rather than defaulted to zero`() {
        val doc = """{"schemaVersion":1,"metadata":{},"frames":[
            {"t":0,"px":1.0,"pz":0,"rx":0,"ry":0,"rz":0}]}"""
        assertEquals(
            ReplayStore.Refusal.BAD_COORDINATE,
            (ReplayStore.fromJson(doc) as ReplayStore.Decoded.Refused).reason,
        )
    }

    @Test
    fun `a missing or negative timestamp is refused`() {
        for (doc in listOf(
            """{"schemaVersion":1,"metadata":{},"frames":[{"px":0,"py":0,"pz":0,"rx":0,"ry":0,"rz":0}]}""",
            """{"schemaVersion":1,"metadata":{},"frames":[{"t":-1,"px":0,"py":0,"pz":0,"rx":0,"ry":0,"rz":0}]}""",
        )) {
            assertEquals(
                ReplayStore.Refusal.BAD_TIMESTAMP,
                (ReplayStore.fromJson(doc) as ReplayStore.Decoded.Refused).reason,
            )
        }
    }

    @Test
    fun `an over-long input label is refused`() {
        val long = "x".repeat(ReplayStore.MAX_INPUT_LEN + 1)
        val r = replay(frame(0L, inputs = setOf(long)))
        assertEquals(
            ReplayStore.Refusal.INPUT_TOO_LONG,
            (ReplayStore.fromJson(ReplayStore.toJson(r)) as ReplayStore.Decoded.Refused).reason,
        )
    }

    @Test
    fun `too many input labels are refused`() {
        val many = (0 until ReplayStore.MAX_INPUTS_PER_FRAME + 1).map { "k$it" }.toSet()
        val r = replay(frame(0L, inputs = many))
        assertEquals(
            ReplayStore.Refusal.TOO_MANY_INPUTS,
            (ReplayStore.fromJson(ReplayStore.toJson(r)) as ReplayStore.Decoded.Refused).reason,
        )
    }

    @Test
    fun `the frame cap refuses an over-long document`() {
        val arr = org.json.JSONArray()
        repeat(5) {
            arr.put(
                JSONObject()
                    .put("t", it)
                    .put("px", 1.0).put("py", 2.0).put("pz", 3.0)
                    .put("rx", 0.0).put("ry", 0.0).put("rz", 0.0),
            )
        }
        val doc = JSONObject()
            .put("schemaVersion", 1)
            .put("metadata", JSONObject())
            .put("frames", arr)
            .toString()

        assertTrue(ReplayStore.fromJson(doc, maxFrames = 5) is ReplayStore.Decoded.Ok)
        // The cap is a refusal, not a truncation: a partial decode would look
        // like a complete replay of a shorter session.
        assertEquals(
            ReplayStore.Refusal.TOO_MANY_FRAMES,
            (ReplayStore.fromJson(doc, maxFrames = 4) as ReplayStore.Decoded.Refused).reason,
        )
        assertEquals(
            ReplayStore.Refusal.TOO_MANY_FRAMES,
            (ReplayStore.fromJson(doc, maxFrames = 0) as ReplayStore.Decoded.Refused).reason,
        )
    }

    @Test
    fun `frames missing coordinates are refused, not defaulted to zero`() {
        // Guards the earlier version of this test, which asserted Ok on frames
        // carrying only a timestamp and so passed for the wrong reason.
        val arr = org.json.JSONArray()
        arr.put(JSONObject().put("t", 0))
        val doc = JSONObject()
            .put("schemaVersion", 1)
            .put("metadata", JSONObject())
            .put("frames", arr)
            .toString()
        assertEquals(
            ReplayStore.Refusal.BAD_COORDINATE,
            (ReplayStore.fromJson(doc) as ReplayStore.Decoded.Refused).reason,
        )
    }

    @Test
    fun `an implausibly large document is refused without parsing`() {
        val huge = "x".repeat(ReplayStore.MAX_DOCUMENT_CHARS + 1)
        assertEquals(
            ReplayStore.Refusal.MALFORMED,
            (ReplayStore.fromJson(huge) as ReplayStore.Decoded.Refused).reason,
        )
    }

    @Test
    fun `a missing input array decodes as no inputs`() {
        val doc = """{"schemaVersion":1,"metadata":{},"frames":[
            {"t":0,"px":0,"py":0,"pz":0,"rx":0,"ry":0,"rz":0}]}"""
        val back = (ReplayStore.fromJson(doc) as ReplayStore.Decoded.Ok).replay
        assertTrue(back.frames[0].inputs.isEmpty())
    }

    @Test
    fun `safe names reject anything that could escape the replays directory`() {
        assertTrue(ReplayStore.isSafeName("session-2026-10-11_01"))
        assertTrue(ReplayStore.isSafeName("a"))
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "a\nb", "a b", "a b", "..x")) {
            assertTrue("must refuse '$bad'", !ReplayStore.isSafeName(bad))
        }
        assertTrue(!ReplayStore.isSafeName("x".repeat(ReplayStore.MAX_NAME + 1)))
    }

    @Test
    fun `empty replay metadata reports zero duration`() {
        val r = replay()
        assertEquals(0L, r.metadata.durationMs)
        assertNull(null)
    }
}