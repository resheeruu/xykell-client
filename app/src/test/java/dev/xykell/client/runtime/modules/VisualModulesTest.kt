package dev.xykell.client.runtime.modules

import dev.xykell.client.runtime.relay.BedrockPacketIds
import dev.xykell.client.runtime.relay.RelayDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualModulesTest {

    private val setTime = ModuleWire.build(0x0A, ModuleWire.writeVarInt(12000))
    private val other = ModuleWire.build(0x09, byteArrayOf(0, 0, 0))

    private fun bytes(list: List<ByteArray>) = list.map { it.contentToString() }

    private fun text(id: String) = bytes(VisualModules.transform(id, RelayDirection.TO_CLIENT, setTime))

    private fun body(raw: ByteArray) = ModuleWire.readVarInt(raw, ModuleWire.bodyStart(raw) ?: -1)?.first

    // ------------------------------------------------------------- fixtures

    private fun header(id: Int): ByteArray = ModuleWire.writeVarUInt(id)

    /** varint64, which ModuleWire has no writer for. */
    private fun varLong(v: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var x = v
        while (true) {
            if (x and 0x7fL.inv() == 0L) {
                out.write(x.toInt())
                return out.toByteArray()
            }
            out.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
    }

    private fun vec3(x: Float, y: Float, z: Float) =
        ModuleWire.writeF32LE(x) + ModuleWire.writeF32LE(y) + ModuleWire.writeF32LE(z)

    /** AddPlayer 0x0C, field order per registry/bedrock-packets.json. */
    private fun addPlayer(runtimeId: Long, name: String, x: Float, y: Float, z: Float): ByteArray =
        header(BedrockPacketIds.infoOf("AddPlayer")!!.id) +
            ByteArray(16) +
            ModuleWire.writeVarString(name) +
            varLong(runtimeId) +
            byteArrayOf(0) +
            vec3(x, y, z) + vec3(0f, 0f, 0f) +
            ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(90f) + ModuleWire.writeF32LE(90f)

    /** AddEntity 0x0D. */
    private fun addEntity(runtimeId: Long, type: String, x: Float, y: Float, z: Float): ByteArray =
        header(BedrockPacketIds.infoOf("AddEntity")!!.id) +
            varLong(runtimeId) +
            varLong(runtimeId) +
            ModuleWire.writeVarString(type) +
            vec3(x, y, z) + vec3(0f, 0f, 0f) +
            ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f)

    /** MobEffect 0x1C, every field written so the layout is fully exercised. */
    private fun mobEffect(entityId: Long, effect: Int, event: Int = 1): ByteArray =
        header(BedrockPacketIds.infoOf("MobEffect")!!.id) +
            varLong(entityId) +
            byteArrayOf(event.toByte()) +
            ModuleWire.writeVarInt(effect) +
            ModuleWire.writeVarInt(0) +
            byteArrayOf(1) +
            ModuleWire.writeVarInt(30) +
            varLong(1234) +
            byteArrayOf(0)

    /** LevelEvent 0x19: event, vec3f position, data. */
    private fun levelEvent(event: Int, data: Int = 0): ByteArray =
        header(BedrockPacketIds.infoOf("LevelEvent")!!.id) +
            ModuleWire.writeVarInt(event) +
            vec3(1f, 2f, 3f) +
            ModuleWire.writeVarInt(data)

    /** SpawnParticleEffect 0x76: u8 dimension, zigzag64 id, vec3f, name. */
    private fun spawnParticle(name: String): ByteArray =
        header(BedrockPacketIds.infoOf("SpawnParticleEffect")!!.id) +
            byteArrayOf(0) +
            ModuleWire.writeVarUInt(2) +
            vec3(1f, 2f, 3f) +
            ModuleWire.writeVarString(name)

    /** PlayerFog 0xA0: varint count then that many names. */
    private fun playerFog(vararg stack: String): ByteArray =
        header(BedrockPacketIds.infoOf("PlayerFog")!!.id) +
            ModuleWire.writeVarUInt(stack.size) +
            stack.fold(ByteArray(0)) { acc, s -> acc + ModuleWire.writeVarString(s) }

    /** UpdateBlock 0x15: three block coords, runtime id, flags, layer. */
    private fun updateBlock(x: Int, y: Int, z: Int, blockId: Int): ByteArray =
        header(BedrockPacketIds.infoOf("UpdateBlock")!!.id) +
            ModuleWire.writeVarInt(x) + ModuleWire.writeVarInt(y) + ModuleWire.writeVarInt(z) +
            ModuleWire.writeVarUInt(blockId) +
            ModuleWire.writeVarUInt(2) +
            ModuleWire.writeVarUInt(0)

    /** LevelChunk 0x3A prefix: chunk x, chunk z, dimension, sub-chunk count. */
    private fun levelChunk(chunkX: Int, chunkZ: Int, subChunks: Int = 4): ByteArray =
        header(BedrockPacketIds.infoOf("LevelChunk")!!.id) +
            ModuleWire.writeVarInt(chunkX) +
            ModuleWire.writeVarInt(chunkZ) +
            ModuleWire.writeVarInt(0) +
            ModuleWire.writeVarUInt(subChunks) +
            byteArrayOf(0) // cache_enabled, then an opaque payload this repo never reads

    /** SetSpawnPosition 0x2B: player position, dimension, world position. */
    private fun setSpawnPosition(
        px: Int, py: Int, pz: Int,
        wx: Int, wy: Int, wz: Int,
    ): ByteArray =
        header(BedrockPacketIds.infoOf("SetSpawnPosition")!!.id) +
            ModuleWire.writeVarInt(0) +
            ModuleWire.writeVarInt(px) + ModuleWire.writeVarInt(py) + ModuleWire.writeVarInt(pz) +
            ModuleWire.writeVarInt(0) +
            ModuleWire.writeVarInt(wx) + ModuleWire.writeVarInt(wy) + ModuleWire.writeVarInt(wz)

    /** A context with the player at the origin and four nearby entities. */
    private fun world(): ModuleContext {
        val ctx = ModuleContext()
        ctx.updateSelf(1L, 0f, 64f, 0f, true)
        ctx.entities.observe(addPlayer(7L, "steve", 3f, 64f, 0f), ctx.selfRuntimeId)
        ctx.entities.observe(addEntity(9L, "minecraft:zombie", 0f, 64f, 10f), ctx.selfRuntimeId)
        ctx.entities.observe(addEntity(11L, "minecraft:item", 0f, 64f, 2f), ctx.selfRuntimeId)
        ctx.entities.observe(addEntity(13L, "minecraft:zombie", 500f, 64f, 0f), ctx.selfRuntimeId)
        return ctx
    }

    private fun drop(id: String, packet: ByteArray, ctx: ModuleContext = ModuleContext()) =
        VisualModules.transform(id, RelayDirection.TO_CLIENT, packet, ctx)

    // ---------------------------------------------------------------- coverage

    @Test
    fun allVisualIdsClassified() {
        val expected = listOf(
            "esp", "player_esp", "entity_esp", "item_esp", "tracers", "block_tracers",
            "free_look", "free_cam", "xray", "fullbright", "no_invisible", "no_fire",
            "no_weather", "no_blindness", "no_nausea", "chunk_borders", "new_chunks",
            "hole_esp", "spawner_esp", "spawner_ping", "sus_chunk_finder", "bed_esp",
            "waypoints", "minimap", "schematic", "view_model", "zoom", "motion_blur",
            "shader_loader", "custom_crosshair", "hit_effects", "particle_controls",
            "fog_controls", "camera_controls", "block_outline", "item_physics",
            "nametag", "gui_scale", "time_changer", "weather_changer",
        ).map { "xykell.visual.$it" }.toSet()
        assertEquals(expected, VisualModules.IMPLEMENTED + VisualModules.IMPOSSIBLE.keys)
        assertEquals(
            emptySet<String>(),
            VisualModules.IMPLEMENTED.intersect(VisualModules.IMPOSSIBLE.keys),
        )
    }

    /**
     * Pins the implemented set by name. Without this, demoting an id to a
     * pass-through would still leave the coverage tests above green.
     */
    @Test
    fun implementedSetIsPinned() {
        assertEquals(
            setOf(
                "xykell.visual.time_changer",
                "xykell.visual.fullbright",
                "xykell.visual.no_blindness",
                "xykell.visual.no_nausea",
                "xykell.visual.no_weather",
                "xykell.visual.weather_changer",
                "xykell.visual.particle_controls",
                "xykell.visual.fog_controls",
                "xykell.visual.esp",
                "xykell.visual.player_esp",
                "xykell.visual.entity_esp",
                "xykell.visual.item_esp",
                "xykell.visual.nametag",
                "xykell.visual.tracers",
                "xykell.visual.block_tracers",
                "xykell.visual.waypoints",
                "xykell.visual.chunk_borders",
                
                "xykell.visual.new_chunks",
            ),
            VisualModules.IMPLEMENTED,
        )
    }

    @Test
    fun impossibleIdsCarryAReason() {
        for ((id, reason) in VisualModules.IMPOSSIBLE) {
            assertTrue("no reason for $id", reason.length > 20)
        }
    }

    @Test
    fun theIdsThatNeedTheRenderPassStayImpossible() {
        // These change what the CLIENT RENDERER draws. No packet rewrite can
        // reach them, so a data pipeline alone must not promote them.
        for (id in listOf(
            "xykell.visual.xray",
            "xykell.visual.shader_loader",
            "xykell.visual.gui_scale",
            "xykell.visual.motion_blur",
            "xykell.visual.view_model",
            "xykell.visual.zoom",
        )) {
            assertTrue("$id must stay impossible", id in VisualModules.IMPOSSIBLE)
            assertTrue("$id must not be implemented", id !in VisualModules.IMPLEMENTED)
        }
    }

    // ------------------------------------------------------------- SetTime ids

    @Test
    fun timeChangerForcesMidnight() {
        assertEquals(
            bytes(listOf(ModuleWire.build(0x0A, ModuleWire.writeVarInt(18000)))),
            text("xykell.visual.time_changer"),
        )
    }

    @Test
    fun fullbrightForcesNoon() {
        assertEquals(
            bytes(listOf(ModuleWire.build(0x0A, ModuleWire.writeVarInt(6000)))),
            text("xykell.visual.fullbright"),
        )
    }

    @Test
    fun fullbrightDiffersFromTimeChanger() {
        assertTrue(text("xykell.visual.fullbright") != text("xykell.visual.time_changer"))
    }

    @Test
    fun rewrittenTimeIsReadBackFromTheBytes() {
        for (id in setOf("xykell.visual.time_changer", "xykell.visual.fullbright")) {
            assertEquals(id, 1, VisualModules.transform(id, RelayDirection.TO_CLIENT, setTime).size)
            val out = VisualModules.transform(id, RelayDirection.TO_CLIENT, setTime).first()
            assertEquals(id, 0x0A, ModuleWire.id(out))
            assertEquals(id, 1, ModuleWire.varUIntSize(out, 0))
            val wanted = if (id.endsWith("fullbright")) 6000 else 18000
            assertEquals(id, wanted, body(out))
        }
    }

    @Test
    fun multiByteTickIsRewrittenNotAppended() {
        // 1234567 needs three varint bytes; the rewrite must resize the field.
        val big = ModuleWire.build(0x0A, ModuleWire.writeVarInt(1234567))
        val out = VisualModules.transform("xykell.visual.fullbright", RelayDirection.TO_CLIENT, big).first()
        val header = ModuleWire.varUIntSize(big, 0) ?: -1
        val noonSize = ModuleWire.varUIntSize(ModuleWire.writeVarInt(6000), 0) ?: -1
        assertEquals(header + noonSize, out.size)
        assertEquals(6000, body(out))
    }

    @Test
    fun rewritingTwiceIsStable() {
        val once = VisualModules.transform("xykell.visual.fullbright", RelayDirection.TO_CLIENT, setTime).first()
        val twice = VisualModules.transform("xykell.visual.fullbright", RelayDirection.TO_CLIENT, once).first()
        assertEquals(bytes(listOf(once)), bytes(listOf(twice)))
    }

    // ----------------------------------------------------- MobEffect 0x1C ids

    @Test
    fun noBlindnessDropsOnlyBlindness() {
        val blindness = mobEffect(1L, VisualModules.EFFECT_BLINDNESS)
        val speed = mobEffect(1L, 1)
        assertEquals(0, drop("xykell.visual.no_blindness", blindness).size)
        assertEquals(bytes(listOf(speed)), bytes(drop("xykell.visual.no_blindness", speed)))
    }

    @Test
    fun noNauseaDropsOnlyNausea() {
        val nausea = mobEffect(1L, VisualModules.EFFECT_NAUSEA)
        val blindness = mobEffect(1L, VisualModules.EFFECT_BLINDNESS)
        assertEquals(0, drop("xykell.visual.no_nausea", nausea).size)
        assertEquals(bytes(listOf(blindness)), bytes(drop("xykell.visual.no_nausea", blindness)))
    }

    @Test
    fun theTwoEffectIdsAreDistinct() {
        assertTrue(VisualModules.EFFECT_BLINDNESS != VisualModules.EFFECT_NAUSEA)
        // Dropping one must not take the other with it.
        assertEquals(1, drop("xykell.visual.no_nausea", mobEffect(1L, VisualModules.EFFECT_BLINDNESS)).size)
        assertEquals(1, drop("xykell.visual.no_blindness", mobEffect(1L, VisualModules.EFFECT_NAUSEA)).size)
    }

    @Test
    fun aTruncatedMobEffectIsForwardedNotDropped() {
        val full = mobEffect(1L, VisualModules.EFFECT_BLINDNESS)
        // Cutting the tail invalidates the layout read, and a relay must never
        // drop a packet it could not fully parse.
        for (id in listOf("xykell.visual.no_blindness", "xykell.visual.no_nausea")) {
            for (cut in 1..full.size - 1) {
                assertEquals(id, 1, drop(id, full.copyOf(cut)).size)
            }
        }
    }

    @Test
    fun mobEffectWithTrailingBytesIsForwarded() {
        val padded = mobEffect(1L, VisualModules.EFFECT_BLINDNESS) + byteArrayOf(7, 7, 7)
        for (id in listOf("xykell.visual.no_blindness", "xykell.visual.no_nausea")) {
            assertEquals(bytes(listOf(padded)), bytes(drop(id, padded)))
        }
    }

    @Test
    fun mobEffectRewritesIgnoreTheServerboundLeg() {
        val blindness = mobEffect(1L, VisualModules.EFFECT_BLINDNESS)
        for (id in listOf("xykell.visual.no_blindness", "xykell.visual.no_nausea")) {
            assertEquals(
                bytes(listOf(blindness)),
                VisualModules.transform(id, RelayDirection.TO_SERVER, blindness).map { it.contentToString() },
            )
        }
    }

    // ------------------------------------------------------ LevelEvent 0x19

    @Test
    fun noWeatherDropsTheStartEvents() {
        for (event in listOf(
            VisualModules.LEVEL_EVENT_START_RAIN,
            VisualModules.LEVEL_EVENT_START_THUNDER,
        )) {
            assertEquals(0, drop("xykell.visual.no_weather", levelEvent(event)).size)
        }
    }

    @Test
    fun noWeatherKeepsTheStopEvents() {
        for (event in listOf(
            VisualModules.LEVEL_EVENT_STOP_RAIN,
            VisualModules.LEVEL_EVENT_STOP_THUNDER,
            3600,
        )) {
            val packet = levelEvent(event)
            assertEquals(bytes(listOf(packet)), bytes(drop("xykell.visual.no_weather", packet)))
        }
    }

    @Test
    fun weatherChangerRewritesStartIntoStop() {
        val cases = mapOf(
            VisualModules.LEVEL_EVENT_START_RAIN to VisualModules.LEVEL_EVENT_STOP_RAIN,
            VisualModules.LEVEL_EVENT_START_THUNDER to VisualModules.LEVEL_EVENT_STOP_THUNDER,
        )
        for ((from, to) in cases) {
            val out = drop("xykell.visual.weather_changer", levelEvent(from)).single()
            assertEquals(0x19, ModuleWire.id(out))
            assertEquals(to, ModuleWire.readVarInt(out, ModuleWire.bodyStart(out) ?: -1)?.first)
            // position and data survive the rewrite byte for byte
            // Everything AFTER the event field must survive byte for byte.
            // (Comparing a fixed trailing window would overlap the event
            // varint itself, which is exactly the field being rewritten.)
            val srcBody = ModuleWire.bodyStart(levelEvent(from)) ?: -1
            val srcEvent = ModuleWire.varUIntSize(levelEvent(from), srcBody) ?: -1
            val srcTail = levelEvent(from).copyOfRange(srcBody + srcEvent, levelEvent(from).size)
            val outTail = out.copyOfRange(srcBody + srcEvent, out.size)
            assertEquals(srcTail.toList(), outTail.toList())
        }
    }

    @Test
    fun weatherChangerLeavesStopEventsAlone() {
        val packet = levelEvent(VisualModules.LEVEL_EVENT_STOP_RAIN)
        assertEquals(bytes(listOf(packet)), bytes(drop("xykell.visual.weather_changer", packet)))
    }

    @Test
    fun weatherIdsAreDistinct() {
        assertEquals(
            setOf(3001, 3002, 3003, 3004),
            setOf(
                VisualModules.LEVEL_EVENT_START_RAIN,
                VisualModules.LEVEL_EVENT_START_THUNDER,
                VisualModules.LEVEL_EVENT_STOP_RAIN,
                VisualModules.LEVEL_EVENT_STOP_THUNDER,
            ),
        )
    }

    @Test
    fun weatherTransformsIgnoreTheServerboundLeg() {
        val rain = levelEvent(VisualModules.LEVEL_EVENT_START_RAIN)
        for (id in listOf("xykell.visual.no_weather", "xykell.visual.weather_changer")) {
            assertEquals(
                bytes(listOf(rain)),
                VisualModules.transform(id, RelayDirection.TO_SERVER, rain).map { it.contentToString() },
            )
        }
    }

    // ------------------------------------------------ SpawnParticleEffect 0x76

    @Test
    fun particleControlsDropsBlockedParticles() {
        val hurt = spawnParticle("minecraft:villager_hurt")
        assertEquals(0, drop("xykell.visual.particle_controls", hurt).size)
    }

    @Test
    fun particleControlsKeepsOtherParticles() {
        val smoke = spawnParticle("minecraft:basic_smoke_particle")
        assertEquals(bytes(listOf(smoke)), bytes(drop("xykell.visual.particle_controls", smoke)))
    }

    @Test
    fun particleControlsHonoursItsSetting() {
        val ctx = ModuleContext()
        ctx.settings["particle_controls.block"] = "minecraft:basic_smoke_particle"
        val smoke = spawnParticle("minecraft:basic_smoke_particle")
        val hurt = spawnParticle("minecraft:villager_hurt")
        assertEquals(0, drop("xykell.visual.particle_controls", smoke, ctx).size)
        assertEquals(bytes(listOf(hurt)), bytes(drop("xykell.visual.particle_controls", hurt, ctx)))
    }

    @Test
    fun particleControlsIgnoresTheServerboundLeg() {
        val hurt = spawnParticle("minecraft:villager_hurt")
        assertEquals(
            bytes(listOf(hurt)),
            VisualModules.transform(
                "xykell.visual.particle_controls", RelayDirection.TO_SERVER, hurt,
            ).map { it.contentToString() },
        )
    }

    // ---------------------------------------------------------- PlayerFog 0xA0

    @Test
    fun fogControlsClearsTheStack() {
        val out = drop("xykell.visual.fog_controls", playerFog("minecraft:nether_walls")).single()
        assertEquals(0xA0, ModuleWire.id(out))
        val body = ModuleWire.bodyStart(out) ?: -1
        assertEquals(0, ModuleWire.readVarUInt(out, body)?.first)
        // body must be exactly the count varuint and nothing after it
        assertEquals(body + 1, out.size)
    }

    @Test
    fun fogControlsWritesTheConfiguredStack() {
        val ctx = ModuleContext()
        ctx.settings["fog_controls.stack"] = "minecraft:creeper, minecraft:nether_walls"
        val out = drop("xykell.visual.fog_controls", playerFog(), ctx).single()
        val stack = readFog(out)
        assertEquals(listOf("minecraft:creeper", "minecraft:nether_walls"), stack)
    }

    @Test
    fun fogControlsIsStableWhenTheStackAlreadyMatches() {
        val ctx = ModuleContext()
        ctx.settings["fog_controls.stack"] = "minecraft:creeper"
        val packet = playerFog("minecraft:creeper")
        assertEquals(bytes(listOf(packet)), bytes(drop("xykell.visual.fog_controls", packet, ctx)))
    }

    @Test
    fun fogControlsIgnoresTheServerboundLeg() {
        val packet = playerFog("minecraft:nether_walls")
        assertEquals(
            bytes(listOf(packet)),
            VisualModules.transform(
                "xykell.visual.fog_controls", RelayDirection.TO_SERVER, packet,
            ).map { it.contentToString() },
        )
    }

    private fun readFog(raw: ByteArray): List<String> {
        val body = ModuleWire.bodyStart(raw) ?: return emptyList()
        val (count, after) = ModuleWire.readVarUInt(raw, body) ?: return emptyList()
        var at = after
        val out = ArrayList<String>(count)
        repeat(count) {
            val entry = ModuleWire.readVarString(raw, at) ?: return out
            out.add(entry.first)
            at = entry.second
        }
        return out
    }

    // ------------------------------------------------------------- ESP views

    @Test
    fun espTargetsAreSortedByDistanceAndCarryTheEntityData() {
        // Radius must cover the far entity at x=500; the expected ordering below is
        // the distance-sorted one that radius produces.
        val targets = VisualModules.targets(world(), 1000f, playersOnly = false)
        assertEquals(listOf(11L, 7L, 9L, 13L), targets.map { it.runtimeId })
        val steve = targets[1]
        assertEquals("steve", steve.name)
        assertTrue(steve.isPlayer)
        assertEquals(3f, steve.x, 0.0001f)
        assertEquals(3f, steve.distance, 0.0001f)
        // a non-player is named by its AddEntity type
        assertEquals("minecraft:zombie", targets[2].name)
        assertTrue(!targets[2].isPlayer)
    }

    @Test
    fun maxDistanceActuallyFilters() {
        val ctx = world()
        assertEquals(4, VisualModules.targets(ctx, 1000f, playersOnly = false).size)
        val near = VisualModules.targets(ctx, 5f, playersOnly = false)
        assertEquals(listOf(11L, 7L), near.map { it.runtimeId })
        assertEquals(0, VisualModules.targets(ctx, 0.5f, playersOnly = false).size)
    }

    @Test
    fun playerEspKeepsOnlyPlayers() {
        assertEquals(
            listOf(7L),
            VisualModules.playerTargets(world(), 1000f).map { it.runtimeId },
        )
        assertEquals(listOf(7L), VisualModules.targets(world(), 64f, playersOnly = true).map { it.runtimeId })
    }

    @Test
    fun entityEspExcludesPlayersAndFiltersByType() {
        val ctx = world()
        assertEquals(
            listOf(11L, 9L, 13L),
            VisualModules.entityTargets(ctx, 1000f, "").map { it.runtimeId },
        )
        assertEquals(
            listOf(9L, 13L),
            VisualModules.entityTargets(ctx, 1000f, "zombie").map { it.runtimeId },
        )
        assertEquals(0, VisualModules.entityTargets(ctx, 1000f, "creeper").size)
    }

    @Test
    fun itemEspSelectsDroppedItemTypes() {
        assertEquals(
            listOf(11L),
            VisualModules.itemTargets(world(), 1000f).map { it.runtimeId },
        )
        assertEquals(0, VisualModules.itemTargets(world(), 1f).size)
    }

    @Test
    fun nametagOnlyCoversNamedPlayers() {
        assertEquals(
            listOf(7L),
            VisualModules.namedTargets(world(), 1000f).map { it.runtimeId },
        )
    }

    @Test
    fun espExcludesTheLocalPlayer() {
        val ctx = ModuleContext()
        ctx.updateSelf(4L, 0f, 64f, 0f, true)
        ctx.entities.observe(addPlayer(4L, "me", 0f, 64f, 0f), ctx.selfRuntimeId)
        ctx.entities.observe(addPlayer(5L, "you", 2f, 64f, 0f), ctx.selfRuntimeId)
        assertEquals(listOf(5L), VisualModules.targets(ctx, 64f, playersOnly = false).map { it.runtimeId })
    }

    @Test
    fun espIsEmptyWithNoEntities() {
        assertEquals(0, VisualModules.targets(ModuleContext(), 64f, playersOnly = false).size)
    }

    @Test
    fun tracersStartAtThePlayerAndEndAtTheTarget() {
        val ctx = world()
        val lines = VisualModules.tracers(ctx, 64f)
        assertEquals(3, lines.size) // the 500-block zombie is out of range
        val first = lines.first()
        assertEquals(0f, first.fromX, 0.0001f)
        assertEquals(64f, first.fromY, 0.0001f)
        assertEquals(0f, first.fromZ, 0.0001f)
        assertEquals(0f, first.toX, 0.0001f)
        assertEquals(2f, first.toZ, 0.0001f)
        assertEquals(2f, first.length, 0.0001f)
    }

    // -------------------------------------------------------- block_tracers

    @Test
    fun blockMarkersReadTheUpdateBlockPosition() {
        val ctx = world()
        val marks = VisualModules.blockMarkers(ctx, updateBlock(2, 64, -1, 17), 64f)
        assertEquals(1, marks.size)
        assertEquals(2, marks[0].x)
        assertEquals(64, marks[0].y)
        assertEquals(-1, marks[0].z)
        assertEquals(17, marks[0].blockRuntimeId)
    }

    @Test
    fun blockMarkersFilterByDistance() {
        val ctx = world()
        assertEquals(0, VisualModules.blockMarkers(ctx, updateBlock(500, 64, 0, 17), 64f).size)
        assertEquals(1, VisualModules.blockMarkers(ctx, updateBlock(500, 64, 0, 17), 1000f).size)
    }

    @Test
    fun blockTracersAreLinesOutOfThePlayer() {
        val ctx = world()
        val line = VisualModules.blockTracers(ctx, updateBlock(2, 64, 0, 17), 64f).single()
        assertEquals(0f, line.fromX, 0.0001f)
        assertEquals(2.5f, line.toX, 0.0001f)
        assertEquals(64.5f, line.toY, 0.0001f)
    }

    @Test
    fun blockMarkersIgnoreAPacketThatIsNotAnUpdateBlock() {
        val ctx = world()
        assertEquals(0, VisualModules.blockMarkers(ctx, setTime, 1000f).size)
        assertEquals(0, VisualModules.blockMarkers(ctx, ByteArray(0), 1000f).size)
    }

    // ---------------------------------------------------------- chunk views

    @Test
    fun chunkMarksCarryBothCoordinateSpaces() {
        val marks = VisualModules.chunkMarks(world(), levelChunk(3, -2, 7), 1000f)
        assertEquals(1, marks.size)
        assertEquals(3, marks[0].chunkX)
        assertEquals(-2, marks[0].chunkZ)
        assertEquals(48, marks[0].worldX)
        assertEquals(-32, marks[0].worldZ)
        assertEquals(7, marks[0].subChunks)
    }

    @Test
    fun chunkMarksFilterByDistance() {
        assertEquals(0, VisualModules.chunkMarks(world(), levelChunk(30, 0), 64f).size)
        assertEquals(1, VisualModules.chunkMarks(world(), levelChunk(30, 0), 1000f).size)
    }

    @Test
    fun chunkBordersAreTheFourEdgesOfTheChunk() {
        val lines = VisualModules.chunkBorderLines(world(), levelChunk(1, 1))
        assertEquals(4, lines.size)
        val xs = lines.flatMap { listOf(it.fromX, it.toX) }.map { it.toInt() }.distinct().sorted()
        val zs = lines.flatMap { listOf(it.fromZ, it.toZ) }.map { it.toInt() }.distinct().sorted()
        assertEquals(listOf(16, 32), xs)
        assertEquals(listOf(16, 32), zs)
    }

    @Test
    fun newChunksOnlyReportsChunksFromOutsideTheViewRadius() {
        val ctx = world()
        assertEquals(0, VisualModules.newChunkMarks(ctx, levelChunk(0, 0)).size)
        assertEquals(1, VisualModules.newChunkMarks(ctx, levelChunk(20, 0)).size)
        // the radius is a setting, and it moves the boundary
        ctx.settings["chunk_radius"] = "32"
        assertEquals(0, VisualModules.newChunkMarks(ctx, levelChunk(20, 0)).size)
    }

    @Test
    fun chunkViewsIgnoreAPacketThatIsNotALevelChunk() {
        assertEquals(0, VisualModules.chunkMarks(world(), setTime, 1000f).size)
        assertEquals(0, VisualModules.chunkBorderLines(world(), other).size)
        assertEquals(0, VisualModules.newChunkMarks(world(), ByteArray(0)).size)
    }

    // -------------------------------------------------------------- waypoints

    @Test
    fun spawnWaypointsComeFromSetSpawnPosition() {
        val marks = VisualModules.spawnWaypoints(world(), setSpawnPosition(10, 64, -10, -100, 64, 200))
        assertEquals(listOf("spawn", "world_spawn"), marks.map { it.name })
        assertEquals(10f, marks[0].x, 0.0001f)
        assertEquals(-10f, marks[0].z, 0.0001f)
        assertEquals(-100f, marks[1].x, 0.0001f)
        assertEquals(200f, marks[1].z, 0.0001f)
        assertTrue(marks[1].distance > marks[0].distance)
    }

    @Test
    fun spawnWaypointsAreEmptyForAnythingElse() {
        assertEquals(0, VisualModules.spawnWaypoints(world(), setTime).size)
        assertEquals(0, VisualModules.spawnWaypoints(world(), ByteArray(0)).size)
    }

    // ------------------------------------------------------------ pass-through

    @Test
    fun nonTimePacketUntouched() {
        for (id in VisualModules.IMPLEMENTED) {
            assertEquals(bytes(listOf(other)), bytes(VisualModules.transform(id, RelayDirection.TO_CLIENT, other)))
        }
    }

    @Test
    fun headerOnlyPacketUntouched() {
        val stub = ModuleWire.build(0x0A)
        for (id in VisualModules.IMPLEMENTED) {
            assertEquals(bytes(listOf(stub)), bytes(VisualModules.transform(id, RelayDirection.TO_CLIENT, stub)))
        }
    }

    @Test
    fun malformedBytesUntouched() {
        val junk = byteArrayOf(0x8F.toByte(), 0xFF.toByte(), 0x7F)
        for (id in VisualModules.IMPLEMENTED) {
            assertEquals(bytes(listOf(junk)), bytes(VisualModules.transform(id, RelayDirection.TO_CLIENT, junk)))
            assertEquals(1, VisualModules.transform(id, RelayDirection.TO_CLIENT, junk).size)
            assertEquals(
                bytes(listOf(ByteArray(0))),
                bytes(VisualModules.transform(id, RelayDirection.TO_CLIENT, ByteArray(0))),
            )
        }
    }

    @Test
    fun setTimeWithTrailingBytesUntouched() {
        val padded = ModuleWire.build(0x0A, ModuleWire.writeVarInt(12000), byteArrayOf(9, 9, 9))
        for (id in VisualModules.IMPLEMENTED) {
            assertEquals(bytes(listOf(padded)), bytes(VisualModules.transform(id, RelayDirection.TO_CLIENT, padded)))
        }
    }

    @Test
    fun impossibleIdIsForwardedUntouched() {
        for (id in VisualModules.IMPOSSIBLE.keys) {
            assertEquals(bytes(listOf(setTime)), bytes(VisualModules.transform(id, RelayDirection.TO_CLIENT, setTime)))
        }
    }

    @Test
    fun unknownIdIsForwardedUntouched() {
        assertEquals(bytes(listOf(setTime)), bytes(VisualModules.transform("xykell.nope", RelayDirection.TO_CLIENT, setTime)))
    }

    @Test
    fun `time transforms ignore the clientbound leg`() {
        val time = ModuleWire.build(0x0A, ModuleWire.writeVarInt(1000))
        val out = VisualModules.transform(
            "xykell.visual.fullbright", RelayDirection.TO_SERVER, time,
        )
        assertEquals(listOf(time.contentToString()), out.map { it.contentToString() })
    }
}