package dev.xykell.client.runtime.observation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Chat presentation policy persistence.
 *
 * The policy is user-local presentation only, so the contract under test is
 * the refusal contract: a newer schema, an oversized rule list or a malformed
 * document must not be half-applied, and one bad nickname must not discard
 * the good ones.
 */
class ChatPolicyTest {

    private lateinit var policy: ChatPolicy

    @Before
    fun setUp() {
        policy = ChatPolicy()
        policy.filter.replace(
            ChatFilter.Config(
                showTimestamps = false,
                timestampFormat = ChatFilter.Config.DEFAULT.timestampFormat,
                rules = listOf(
                    ChatFilter.Rule(
                        id = "r1",
                        pattern = "spam",
                        action = ChatFilter.Action.HIDE,
                        caseSensitive = false,
                    ),
                ),
            ),
        )
        policy.nicknames.set("Steve", "Steve1")
        policy.showTimestamps = false
    }

    private fun doc(block: JSONObject.() -> Unit = {}): String = JSONObject()
        .put("schemaVersion", ChatPolicy.SCHEMA_VERSION)
        .put("showTimestamps", true)
        .put("rules", JSONArray())
        .put("nicknames", JSONObject())
        .apply(block)
        .toString()

    @Test
    fun roundTrip_preservesPolicy() {
        val restored = ChatPolicy()
        assertTrue(restored.deserialize(policy.serialize()))
        assertEquals(policy.showTimestamps, restored.showTimestamps)
        assertEquals(1, restored.filter.rules().size)
        assertEquals("spam", restored.filter.rules()[0].pattern)
        assertEquals(mapOf("Steve" to "Steve1"), restored.nicknames.entries())
    }

    @Test
    fun blankDocument_isAcceptedAsNoOp() {
        assertTrue(policy.deserialize(""))
        assertEquals(1, policy.filter.rules().size)
    }

    @Test
    fun newerSchemaVersion_isRefusedWholesale() {
        policy.showTimestamps = false

        assertFalse(policy.deserialize(doc { put("schemaVersion", ChatPolicy.SCHEMA_VERSION + 1) }))

        // Refused before anything is applied: state is untouched.
        assertFalse(policy.showTimestamps)
        assertEquals(1, policy.filter.rules().size)
    }

    @Test
    fun malformedDocument_isRefusedAndLeavesPolicyIntact() {
        assertFalse(policy.deserialize("{not json"))
        assertEquals(1, policy.filter.rules().size)
        assertEquals(mapOf("Steve" to "Steve1"), policy.nicknames.entries())
    }

    @Test
    fun oversizedRuleList_isRefused() {
        val rules = JSONArray()
        for (i in 0..ChatFilter.MAX_RULES) {
            rules.put(
                JSONObject()
                    .put("id", "r$i")
                    .put("pattern", "p$i")
                    .put("action", "HIDE")
                    .put("caseSensitive", false),
            )
        }
        assertFalse(policy.deserialize(doc { put("rules", rules) }))
    }

    @Test
    fun singleBadNickname_isSkippedNotFatal() {
        val nick = JSONObject()
            .put("Alex", "Alex1")
            .put("x".repeat(64), "bad") // over NicknameMap's 32-char limit
        val bad = nick.keys().asSequence().first { it.length > 32 }

        assertTrue(policy.deserialize(doc { put("nicknames", nick) }))

        val entries = policy.nicknames.entries()
        assertEquals(1, entries.size)
        assertEquals("Alex1", entries["Alex"])
        assertTrue(bad.isNotEmpty())
    }

    @Test
    fun timestampFlag_roundTrips() {
        policy.showTimestamps = true
        val restored = ChatPolicy()
        assertTrue(restored.deserialize(policy.serialize()))
        assertTrue(restored.showTimestamps)
        assertTrue(restored.config().showTimestamps)
    }
}
