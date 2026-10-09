package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.cheat.MacroStep
import dev.xykell.client.runtime.relay.PlayerListTable
import dev.xykell.client.runtime.relay.RelayDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The dispatcher's own contract: which ids it considers, in what order, that
 * enabled ids actually change bytes, that a disabled set is a pass-through, and
 * that the tap plans fire off recorded state rather than a timer alone.
 *
 * Without this suite a deleted `RelayListener.PASS` swap would look fine — the
 * category suites all still pass, because each tests its own object directly.
 */
class ModuleRuntimeTest {

    private val seed = Random(1234)

    private fun runtime(
        vararg on: String,
        settings: Map<String, String> = emptyMap(),
    ) = ModuleRuntime({ id -> id in on.toSet() }, settings, seed)

    private fun rendered(out: List<ByteArray>) = out.map { it.contentToString() }

    /** SetHealth 0x2A — dropped by disabler, untouched by everything else. */
    private val setHealth = ModuleWire.build(0x2A, ModuleWire.writeVarInt(20))

    /** SetTime 0x0A — rewritten by fullbright and time_changer. */
    private val setTime = ModuleWire.build(0x0A, ModuleWire.writeVarInt(1000))

    private fun bytes(raw: ByteArray) = raw.contentToString()

    // ------------------------------------------------------------- inventory

    @Test
    fun `order covers every implemented id exactly once`() {
        val dupes = ModuleRuntime.ORDER.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals("duplicate ids in ORDER", emptySet<String>(), dupes)
        assertEquals(
            "ORDER must name every IMPLEMENTED/READERS id",
            ModuleRuntime.ALL_IMPLEMENTED,
            ModuleRuntime.ORDER.toSet(),
        )
    }

    @Test
    fun `every order id resolves to its own category`() {
        for (id in ModuleRuntime.ORDER) {
            assertNotNull("$id has no category", ModuleRuntime.categoryOf(id))
        }
    }

    @Test
    fun `planned ids are implemented ids`() {
        assertTrue(
            "a planned id is not implemented",
            ModuleRuntime.PLANNED.all { it in ModuleRuntime.ALL_IMPLEMENTED },
        )
    }

    // -------------------------------------------------------------- dispatch

    @Test
    fun `nothing enabled forwards every packet byte-identical`() {
        val out = runtime().transform(RelayDirection.TO_CLIENT, setHealth)
        assertEquals(listOf(bytes(setHealth)), rendered(out))
    }

    @Test
    fun `disabler drops set health when enabled`() {
        val out = runtime("xykell.misc.disabler").transform(RelayDirection.TO_CLIENT, setHealth)
        assertEquals(emptyList<String>(), rendered(out))
    }

    @Test
    fun `a disabled module does not drop its packet`() {
        val out = runtime("xykell.misc.anti_weather")
            .transform(RelayDirection.TO_CLIENT, setHealth)
        assertEquals(listOf(bytes(setHealth)), rendered(out))
    }

    @Test
    fun `enabled id rewrites the bytes it claims to`() {
        val on = runtime("xykell.visual.fullbright").transform(RelayDirection.TO_CLIENT, setTime)
        assertTrue("fullbright did not rewrite SetTime", rendered(on) != listOf(bytes(setTime)))

        val off = runtime().transform(RelayDirection.TO_CLIENT, setTime)
        assertEquals(listOf(bytes(setTime)), rendered(off))
    }

    @Test
    fun `every enabled id leaves an unrelated packet untouched`() {
        // SetHealth is not a packet any implemented module claims, so an
        // enabled set must not corrupt it. This is the regression that would
        // show up if a module parsed at the wrong offset.
        for (id in ModuleRuntime.ALL_IMPLEMENTED) {
            val out = runtime(id).transform(RelayDirection.TO_CLIENT, setHealth)
            if (id == "xykell.misc.disabler") continue // that one drops it by design
            assertEquals(
                "$id corrupted an unrelated packet",
                listOf(bytes(setHealth)),
                rendered(out),
            )
        }
    }

    @Test
    fun `enablement is re-read per packet, not cached`() {
        var on = false
        val rt = ModuleRuntime({ id -> id == "xykell.misc.disabler" && on }, emptyMap(), seed)
        assertEquals(listOf(bytes(setHealth)), rendered(rt.transform(RelayDirection.TO_CLIENT, setHealth)))
        on = true
        assertEquals(emptyList<String>(), rendered(rt.transform(RelayDirection.TO_CLIENT, setHealth)))
        on = false
        assertEquals(listOf(bytes(setHealth)), rendered(rt.transform(RelayDirection.TO_CLIENT, setHealth)))
    }

    @Test
    fun `settings reach the context`() {
        val rt = runtime(settings = mapOf("knockback_scale" to "0.25"))
        assertEquals(0.25f, rt.ctx.number("knockback_scale", 1f), 0.0001f)
    }

    @Test
    fun `reset clears the session state`() {
        val rt = runtime()
        rt.ctx.recordItemCooldown(32)
        rt.onTick()
        rt.reset()
        assertEquals(0, rt.ctx.tick.toInt())
        assertEquals(0, rt.ctx.itemCooldownTicks)
    }

    // ------------------------------------------------------- session bookkeeping

    /**
     * The entity table must be fed by the runtime itself, not by a module that
     * happens to be enabled. Before this, `observe` was called from nowhere and
     * every derived view silently saw zero entities.
     */
    @Test
    fun `entity table is populated with nothing enabled`() {
        val rt = ModuleRuntime({ false }, emptyMap(), seed)
        // AddEntity 0x0d: runtimeId, uniqueId, type, xyz, velocity, rotation.
        rt.transform(RelayDirection.TO_CLIENT, addEntity(runtimeId = 42, x = 10f))
        assertEquals(1, rt.ctx.entities.size)
        assertEquals(10f, rt.ctx.entities.get(42)!!.x, 0.001f)
    }

    /**
     * Session bookkeeping must not depend on a movement module being switched
     * on: with everything off the self id was never learned, which made the
     * entity table count the player as a target and left `speed` unprimed.
     */
    @Test
    fun `self position is learned with nothing enabled`() {
        val rt = ModuleRuntime({ false }, emptyMap(), seed)
        rt.transform(RelayDirection.TO_SERVER, movePlayer(runtimeId = 7, x = 1f))
        assertEquals(7L, rt.ctx.selfRuntimeId)
        assertEquals(1f, rt.ctx.selfX, 0.001f)
    }

    @Test
    fun `the player's own entity is never counted as a target`() {
        val rt = ModuleRuntime({ false }, emptyMap(), seed)
        rt.transform(RelayDirection.TO_SERVER, movePlayer(runtimeId = 7, x = 1f))
        rt.transform(RelayDirection.TO_CLIENT, addEntity(runtimeId = 7, x = 1f))
        assertEquals("self was counted as an entity", 0, rt.ctx.entities.size)
        rt.transform(RelayDirection.TO_CLIENT, addEntity(runtimeId = 9, x = 5f))
        assertEquals(1, rt.ctx.entities.size)
    }

    @Test
    fun `a server-sent position does not become the self position`() {
        val rt = ModuleRuntime({ false }, emptyMap(), seed)
        rt.transform(RelayDirection.TO_CLIENT, movePlayer(runtimeId = 99, x = 500f))
        assertEquals(
            "a server-supplied position was trusted as the player's own",
            Long.MIN_VALUE,
            rt.ctx.selfRuntimeId,
        )
    }

    /** MovePlayer 0x13 in registry field order. */
    private fun movePlayer(runtimeId: Int, x: Float): ByteArray = ModuleWire.build(
        0x13,
        ModuleWire.writeVarUInt(runtimeId),
        ModuleWire.writeF32LE(x),
        ModuleWire.writeF32LE(64f),
        ModuleWire.writeF32LE(64f),
        ModuleWire.writeF32LE(0f),
        ModuleWire.writeF32LE(90f),
        ModuleWire.writeF32LE(0f),
        byteArrayOf(0),
        byteArrayOf(1),
    )

    /** AddEntity 0x0d: uniqueId, runtimeId, type, position, velocity, rotation. */
    private fun addEntity(runtimeId: Int, x: Float): ByteArray = ModuleWire.build(
        0x0d,
        ModuleWire.writeVarUInt(runtimeId + 1),
        ModuleWire.writeVarUInt(runtimeId),
        ModuleWire.writeVarString("minecraft:pig"),
        ModuleWire.writeF32LE(x),
        ModuleWire.writeF32LE(64f),
        ModuleWire.writeF32LE(64f),
        ModuleWire.writeF32LE(0f),
        ModuleWire.writeF32LE(0f),
        ModuleWire.writeF32LE(0f),
        ModuleWire.writeF32LE(0f),
        ModuleWire.writeF32LE(90f),
        ModuleWire.writeF32LE(0f),
    )

    // ----------------------------------------------------------------- plans

    @Test
    fun `fast eat plans nothing until its tap point is configured`() {
        val rt = runtime("xykell.player.fast_eat")
        rt.onTick()
        assertEquals(emptyList<MacroStep>(), rt.onTick())
    }

    @Test
    fun `fast eat taps every cooldown once a point is set`() {
        val rt = runtime(
            "xykell.player.fast_eat",
            settings = mapOf(PlayerModules.FAST_EAT_POINT to "0.5,0.5", "intervalTicks" to "4"),
        )
        // onTick advances the counter first, so the first call lands on tick 1
        // and ticks 1..3 are not due; tick 4 is.
        assertEquals(emptyList<MacroStep>(), rt.onTick())
        assertEquals(emptyList<MacroStep>(), rt.onTick())
        assertEquals(emptyList<MacroStep>(), rt.onTick())
        assertEquals(1, rt.onTick().size)
        assertEquals(emptyList<MacroStep>(), rt.onTick())
    }

    @Test
    fun `plans are ordered by the declared order`() {
        val rt = ModuleRuntime(
            // Only the four original plans: the three gesture ids have no point
            // configured here and contribute nothing.
            { it in setOf(
                "xykell.player.fast_eat",
                "xykell.player.fast_interact",
                "xykell.automation.auto_eat",
                "xykell.automation.auto_fish",
            ) },
            mapOf(
                PlayerModules.FAST_EAT_POINT to "0.5,0.5",
                PlayerModules.FAST_INTERACT_POINT to "0.5,0.4",
                AutomationModules.AUTO_EAT_POINT to "0.5,0.3",
                AutomationModules.AUTO_FISH_POINT to "0.5,0.2",
                // every plan on every tick, so ORDER is the only thing under test
                "intervalTicks" to "1",
            ),
            seed,
        )
        repeat(5) { rt.onTick() }
        val points = rt.onTick().map { it.ny }
        // ORDER is automation.auto_eat, automation.auto_fish,
        // player.fast_eat, player.fast_interact. auto_fish has no bite, so the
        // three due plans must come out 0.3, 0.5, 0.4 — declaration order, not
        // the alphabetical order a HashSet would give.
        assertEquals(listOf(0.3, 0.5, 0.4), points)
    }

    // ------------------------------------------------------ the online roster

    private fun playerList(type: Int, fill: Int, name: String?): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0x3f)
        out.add(type.toByte())
        for (i in 0 until 16) out.add(fill.toByte())
        if (name != null) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            out.add(bytes.size.toByte())
            for (b in bytes) out.add(b)
        }
        out.add(0)
        return out.toByteArray()
    }

    /**
     * The relay decodes PlayerList but the HUD reads the observation consumer,
     * so without this hand-off a perfectly decoded roster would never be seen.
     */
    @Test
    fun `roster changes reach the observation sink`() {
        val seen = ArrayList<PlayerListTable.Change>()
        val rt = ModuleRuntime({ true }, onPlayerListChange = { seen.add(it) })
        rt.transform(RelayDirection.TO_CLIENT, playerList(0, 1, "Steve"))
        rt.transform(RelayDirection.TO_CLIENT, playerList(1, 1, null))
        assertEquals(2, seen.size)
        assertTrue(seen[0] is PlayerListTable.Change.Added)
        assertEquals("Steve", (seen[0] as PlayerListTable.Change.Added).name)
        assertTrue(seen[1] is PlayerListTable.Change.Removed)
    }

    @Test
    fun `outbound player list is ignored`() {
        val seen = ArrayList<PlayerListTable.Change>()
        val rt = ModuleRuntime({ true }, onPlayerListChange = { seen.add(it) })
        rt.transform(RelayDirection.TO_SERVER, playerList(0, 1, "Spoofed"))
        assertTrue("the server is the only side that sends this", seen.isEmpty())
    }

    @Test
    fun `the roster is per session and dies with it`() {
        val rt = ModuleRuntime({ true })
        rt.transform(RelayDirection.TO_CLIENT, playerList(0, 1, "Steve"))
        assertEquals(1, rt.ctx.playerList.size)
        rt.reset()
        assertEquals(0, rt.ctx.playerList.size)
    }

    // ------------------------------------------------- input gesture shapes

    /**
     * The three ids that used to be IMPOSSIBLE on "no input surface". Each one
     * must produce the gesture it claims — a cadence tap, a double tap, a HOLD —
     * because a plan that silently produced a plain tap would look identical to
     * a working one in the registry while dropping a stack.
     */
    @Test
    fun `afk clicker taps on its cadence`() {
        val rt = runtime(
            "xykell.combat.afk_clicker",
            settings = mapOf(CombatModules.AFK_CLICKER_POINT to "0.5,0.5", "intervalTicks" to "4"),
        )
        assertEquals(emptyList<MacroStep>(), rt.onTick()) // tick 1, not due
        repeat(2) { rt.onTick() }
        val due = rt.onTick() // tick 4
        assertEquals(1, due.size)
        assertEquals(0.5, due[0].nx, 0.0)
        assertEquals("a cadence tap must not be a hold", 0L, due[0].holdMs)
    }

    @Test
    fun `double click produces two taps at the same point`() {
        val rt = runtime(
            "xykell.combat.double_click",
            settings = mapOf(CombatModules.DOUBLE_CLICK_POINT to "0.4,0.6", "intervalTicks" to "1"),
        )
        rt.onTick()
        val steps = rt.onTick()
        assertEquals(2, steps.size)
        assertEquals(steps[0].nx, steps[1].nx, 0.0)
        assertEquals(steps[0].ny, steps[1].ny, 0.0)
        assertEquals(0.6, steps[0].ny, 0.0)
    }

    @Test
    fun `quick drop produces a hold not a tap`() {
        val rt = runtime(
            "xykell.misc.quick_drop",
            settings = mapOf(MiscModules.QUICK_DROP_POINT to "0.2,0.9"),
        )
        val steps = rt.onTick()
        assertEquals(1, steps.size)
        assertTrue("a drop must be a long press", steps[0].holdMs >= TapPlan.MIN_HOLD_MS)
        assertEquals(0.9, steps[0].ny, 0.0)
    }

    @Test
    fun `a gesture with no configured point taps nothing`() {
        for (id in listOf(
            "xykell.combat.afk_clicker",
            "xykell.combat.double_click",
            "xykell.misc.quick_drop",
        )) {
            val rt = runtime(id, settings = mapOf("intervalTicks" to "1"))
            repeat(3) {
                assertEquals("$id guessed a screen position", emptyList<MacroStep>(), rt.onTick())
            }
        }
    }

    @Test
    fun `hold and double tap stay inside their bounds`() {
        val ctx = ModuleContext(
            settings = mutableMapOf("k" to "0.5,0.5", "holdMs" to "999999"),
        )
        assertTrue(TapPlan.hold(ctx, "k")[0].holdMs <= TapPlan.MAX_HOLD_MS.toLong())
        val negative = ModuleContext(settings = mutableMapOf("k" to "0.5,0.5", "holdMs" to "-5"))
        assertTrue(TapPlan.hold(negative, "k")[0].holdMs >= TapPlan.MIN_HOLD_MS.toLong())
    }

    @Test
    fun `auto fish taps only on the recorded bite tick`() {
        val rt = runtime(
            "xykell.automation.auto_fish",
            settings = mapOf(AutomationModules.AUTO_FISH_POINT to "0.5,0.5"),
        )
        // No bite recorded: nothing fires, ever.
        repeat(5) { assertEquals(emptyList<MacroStep>(), rt.onTick()) }
        // The next tick is the one the bite was announced on, and the reel tap
        // fires on exactly that tick and no other.
        rt.ctx.recordBite(rt.ctx.tick + 1)
        assertEquals(1, rt.onTick().size)
        assertEquals(emptyList<MacroStep>(), rt.onTick())
    }
}