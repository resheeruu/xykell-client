package dev.xykell.client.runtime.observation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Observed-state and chat-presentation tests.
 *
 * Everything here is fed from ObservedState, which only accepts what the
 * read-only observation layer reported. No test fabricates a game value: they
 * assert that unknown stays unknown and that bounds hold.
 */
class ObservedChatTest {

    private lateinit var state: ObservedState
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        state = ObservedState { now }
    }

    private fun line(
        id: String,
        sender: String = "Steve",
        message: String = "hello",
        at: Long = now,
    ) = ObservedState.ChatLine(id, at, sender, message)

    // --- ObservedState: chat

    @Test
    fun observedChatIsRetained() {
        state.onPlayerMessage("e1", now, "Steve", "hi")
        state.onPlayerMessage("e2", now, "Alex", "yo")
        assertEquals(2, state.chatCount)
        assertEquals(listOf("Steve", "Alex"), state.chat.map { it.sender })
    }

    @Test
    fun duplicateEventIdIsSuppressed() {
        assertTrue(state.onPlayerMessage("dup", now, "A", "1"))
        assertFalse(state.onPlayerMessage("dup", now, "A", "1"))
        assertEquals(1, state.chatCount)
    }

    @Test
    fun malformedChatOfferIsRejected() {
        assertFalse(state.onPlayerMessage("", now, "A", "1"))
        assertFalse(state.onPlayerMessage("x".repeat(500), now, "A", "1"))
        assertEquals(0, state.chatCount)
    }

    @Test
    fun chatQueueIsBoundedAndDropsOldest() {
        state.chatCap = 3
        repeat(10) { state.onPlayerMessage("e$it", now, "S", "m$it") }
        assertEquals(3, state.chatCount)
        assertEquals(listOf("e7", "e8", "e9"), state.chat.map { it.eventId })
    }

    @Test
    fun oversizedTextIsTruncated() {
        state.onPlayerMessage("e", now, "S".repeat(1000), "m".repeat(2000))
        assertTrue(state.chat[0].sender.length <= 512)
        assertTrue(state.chat[0].message.length <= 512)
    }

    // --- ObservedState: motion

    @Test
    fun motionIsUnknownUntilTravelledIsObserved() {
        assertNull(state.motion)
        assertNull(state.speed)
    }

    @Test
    fun travelledPopulatesRealCoordinates() {
        state.onPlayerTravelled("t1", now, 10.0, 64.0, -20.0, 90.0, 5.0, 1)
        val m = state.motion!!
        assertEquals(10.0, m.x!!, 1e-9)
        assertEquals(64.0, m.y!!, 1e-9)
        assertEquals(-20.0, m.z!!, 1e-9)
        assertEquals(90.0, m.yawDegrees!!, 1e-9)
        assertTrue(m.anyKnown)
    }

    @Test
    fun speedNeedsTwoSamplesSoItIsNeverGuessed() {
        state.onPlayerTravelled("t1", now, 1.0, 1.0, 1.0, 0.0, 10.0, 1)
        assertNull("one sample cannot yield a rate", state.speed)
        now += 1000
        state.onPlayerTravelled("t2", now, 2.0, 1.0, 1.0, 0.0, 25.0, 1)
        assertEquals(15.0, state.speed!!.metresPerSecond, 1e-6)
        assertEquals(2, state.speed!!.samples)
    }

    @Test
    fun backwardsDistanceDoesNotProduceNegativeSpeed() {
        state.onPlayerTravelled("t1", now, 0.0, 0.0, 0.0, 0.0, 50.0, 1)
        now += 1000
        state.onPlayerTravelled("t2", now, 0.0, 0.0, 0.0, 0.0, 10.0, 1)
        val s = state.speed
        assertTrue("no negative speed", s == null || s.metresPerSecond >= 0.0)
    }

    @Test
    fun nonFiniteMotionNeverReachesTheHud() {
        state.onPlayerTravelled("bad", now, Double.NaN, Double.POSITIVE_INFINITY, 1.0, Double.NaN, 1.0, 1)
        val m = state.motion!!
        assertNull(m.x)
        assertNull(m.y)
        assertNull(m.yawDegrees)
        assertEquals(1.0, m.z!!, 1e-9)
    }

    @Test
    fun clearResetsEverything() {
        state.onPlayerMessage("e", now, "A", "1")
        state.onPlayerTravelled("t", now, 1.0, 1.0, 1.0, 0.0, 1.0, 1)
        state.clear()
        assertEquals(0, state.chatCount)
        assertNull(state.motion)
        assertNull(state.speed)
    }

    // --- ChatFilter

    @Test
    fun hideRuleSuppressesMatchingMessages() {
        val f = ChatFilter()
        f.add(ChatFilter.Rule("spoiler", "spoiler", ChatFilter.Action.HIDE, false))
        val v = f.evaluate(line("e", message = "big spoiler here"), now, NicknameMap())
        assertFalse(v.visible)
    }

    @Test
    fun nonMatchingMessageStaysVisible() {
        val f = ChatFilter()
        f.add(ChatFilter.Rule("ad", "\\badword\\b", ChatFilter.Action.HIDE, false))
        assertTrue(f.evaluate(line("e", message = "hello world"), now, NicknameMap()).visible)
    }

    @Test
    fun caseSensitivityIsHonoured() {
        val ci = ChatFilter()
        ci.add(ChatFilter.Rule("a", "HELLO", ChatFilter.Action.HIDE, false))
        assertFalse(ci.evaluate(line("e", message = "hello"), now, NicknameMap()).visible)
        val cs = ChatFilter()
        cs.add(ChatFilter.Rule("a", "HELLO", ChatFilter.Action.HIDE, true))
        assertTrue(cs.evaluate(line("e", message = "hello"), now, NicknameMap()).visible)
    }

    @Test
    fun highlightRuleMarksButDoesNotHide() {
        val f = ChatFilter()
        f.add(ChatFilter.Rule("h", "important", ChatFilter.Action.HIGHLIGHT, false))
        val v = f.evaluate(line("e", message = "important thing"), now, NicknameMap())
        assertTrue(v.visible)
        assertTrue(v.highlighted)
    }

    @Test
    fun invalidPatternIsInertAndFailsOpen() {
        val f = ChatFilter()
        f.add(ChatFilter.Rule("bad", "([unclosed", ChatFilter.Action.HIDE, false))
        val r = f.rules().single()
        assertFalse(r.valid)
        // Fail open: a broken pattern must never silently hide chat.
        assertTrue(f.evaluate(line("e", message = "anything"), now, NicknameMap()).visible)
    }

    @Test
    fun hideBeatsHighlightRegardlessOfOrder() {
        val f = ChatFilter()
        f.add(ChatFilter.Rule("h", "x", ChatFilter.Action.HIGHLIGHT, false))
        f.add(ChatFilter.Rule("d", "x", ChatFilter.Action.HIDE, false))
        assertFalse(f.evaluate(line("e", message = "x"), now, NicknameMap()).visible)
    }

    @Test
    fun ruleCapsAndValidationAreEnforced() {
        val f = ChatFilter(maxRules = 2)
        assertTrue(f.add(ChatFilter.Rule("a", "a", ChatFilter.Action.HIDE, false)) is ApiResult.Ok)
        assertTrue(f.add(ChatFilter.Rule("b", "b", ChatFilter.Action.HIDE, false)) is ApiResult.Ok)
        assertTrue(f.add(ChatFilter.Rule("c", "c", ChatFilter.Action.HIDE, false)) is ApiResult.Invalid)
        // duplicate id
        assertTrue(f.add(ChatFilter.Rule("a", "a", ChatFilter.Action.HIDE, false)) is ApiResult.Invalid)
        // empty + oversize pattern
        assertTrue(f.add(ChatFilter.Rule("d", "", ChatFilter.Action.HIDE, false)) is ApiResult.Invalid)
        val long = "x".repeat(ChatFilter.MAX_PATTERN + 1)
        assertTrue(f.add(ChatFilter.Rule("e", long, ChatFilter.Action.HIDE, false)) is ApiResult.Invalid)
    }

    @Test
    fun removeAndClearWork() {
        val f = ChatFilter()
        f.add(ChatFilter.Rule("a", "a", ChatFilter.Action.HIDE, false))
        assertTrue(f.remove("a"))
        assertFalse(f.remove("a"))
        f.add(ChatFilter.Rule("b", "b", ChatFilter.Action.HIDE, false))
        f.clear()
        assertTrue(f.rules().isEmpty())
    }

    // --- timestamps + nicknames

    @Test
    fun timestampComesFromTheObservedEventTime() {
        val f = ChatFilter()
        val v = f.evaluate(line("e", at = 1_700_000_000_000L), now, NicknameMap())
        assertNotNull(v.timestamp)
        // 1_700_000_000s = 2023-11-14T22:13:20Z
        assertEquals("22:13:20", v.timestamp)
    }

    @Test
    fun timestampsCanBeTurnedOff() {
        val f = ChatFilter()
        val cfg = ChatFilter.Config.DEFAULT.copy(showTimestamps = false)
        assertNull(f.evaluate(line("e"), now, NicknameMap(), cfg).timestamp)
    }

    @Test
    fun nicknameMappingIsDisplayOnly() {
        val n = NicknameMap()
        assertTrue(n.set("Steve", "S") is ApiResult.Ok)
        val f = ChatFilter()
        val v = f.evaluate(line("e", sender = "Steve"), now, n)
        assertEquals("S", v.displaySender)
        // The underlying observed sender is untouched.
        assertEquals("Steve", v.line.sender)
    }

    @Test
    fun unknownSenderFallsBackToItself() {
        val n = NicknameMap()
        assertEquals("Nobody", n.displayName("Nobody"))
    }

    @Test
    fun nicknameValidationRejectsInjectionShapes() {
        val n = NicknameMap()
        assertTrue(n.set("", "x") is ApiResult.Invalid)
        assertTrue(n.set("s", "") is ApiResult.Invalid)
        assertTrue(n.set("s", "bad\nname") is ApiResult.Invalid)
        assertTrue(n.set("s", "bad name") is ApiResult.Invalid)
        assertTrue(n.set("s", "../../etc") is ApiResult.Invalid)
        assertTrue(n.set("s", "a\\b") is ApiResult.Invalid)
    }

    @Test
    fun nicknameCapIsEnforced() {
        val n = NicknameMap(maxEntries = 2)
        n.set("a", "A")
        n.set("b", "B")
        assertTrue(n.set("c", "C") is ApiResult.Invalid)
        // updating an existing sender still allowed at capacity
        assertTrue(n.set("a", "AA") is ApiResult.Ok)
    }

    @Test
    fun visibleLinesFiltersAndOrdersDeterministically() {
        val f = ChatFilter()
        f.add(ChatFilter.Rule("bad", "spam", ChatFilter.Action.HIDE, false))
        val lines = listOf(
            line("1", message = "hello"),
            line("2", message = "this is spam"),
            line("3", message = "world"),
        )
        val out = f.visibleLines(lines, now, NicknameMap())
        assertEquals(listOf("1", "3"), out.map { it.line.eventId })
        // repeated evaluation is identical
        assertEquals(out.map { it.line.eventId }, f.visibleLines(lines, now, NicknameMap()).map { it.line.eventId })
    }

    @Test
    fun replaceAppliesConfig() {
        val f = ChatFilter()
        f.replace(
            ChatFilter.Config.DEFAULT.copy(
                rules = listOf(ChatFilter.Rule("z", "nope", ChatFilter.Action.HIDE, false)),
            ),
        )
        assertFalse(f.evaluate(line("e", message = "nope"), now, NicknameMap()).visible)
        assertTrue(f.evaluate(line("e", message = "yep"), now, NicknameMap()).visible)
    }

    @Test
    fun formatTimeIsUtcAndZeroPadded() {
        assertEquals("00:00:00", ChatFilter.formatTime(0L))
        assertEquals("23:59:59", ChatFilter.formatTime(86_399_000L))
    }
}
