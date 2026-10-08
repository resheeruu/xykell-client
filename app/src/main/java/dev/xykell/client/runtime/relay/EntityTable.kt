package dev.xykell.client.runtime.relay

/**
 * Live entity table built from the packets the relay already terminates.
 *
 * Populated from AddEntity 0x0D / AddPlayer 0x0C (spawn: runtime id, type,
 * name, position), MovePlayer 0x13 (position update) and RemoveEntity 0x0E
 * (despawn). Ids and field order come from the generated
 * [BedrockPacketIds] table, not from memory.
 *
 * This is what turns "the relay sees no world" from true into false: the relay
 * does see every entity's runtime id and position, it just had nowhere to put
 * them. Targeting modules, distance checks, and the ESP overlay all read from
 * here.
 *
 * Every read is bounds-checked and returns null rather than throwing — one
 * malformed packet must never take the relay down. Bounded: [MAX_ENTITIES]
 * with least-recently-seen eviction, because a hostile or buggy server can
 * otherwise grow this without limit.
 */
class EntityTable(private val maxEntities: Int = MAX_ENTITIES) {

    data class Entity(
        val runtimeId: Long,
        val type: String,
        val name: String?,
        val isPlayer: Boolean,
        val x: Float,
        val y: Float,
        val z: Float,
        val pitch: Float,
        val yaw: Float,
        /** Kept separately from [yaw]: free-look and aim modules need the head
         *  angle, and collapsing the two loses exactly the distinction they exist
         *  to use. */
        val headYaw: Float,
        val lastSeenTick: Long,
    )

    private val entities = LinkedHashMap<Long, Entity>()

    /**
     * Bounded position history per entity, oldest first.
     *
     * The table keeps only the latest position, so a module that needs to send
     * an entity *back* to where it was (backtrack) has nothing to replay. This
     * ring is that memory. It is bounded per entity and cleared on eviction so
     * it cannot grow with session length.
     */
    private val history = LinkedHashMap<Long, ArrayDeque<Sample>>()

    var tick: Long = 0
        private set

    val size: Int get() = entities.size

    fun all(): List<Entity> = entities.values.toList()

    fun get(runtimeId: Long): Entity? = entities[runtimeId]

    /** One historical position. [tick] is the logical tick it was seen on. */
    data class Sample(val tick: Long, val x: Float, val y: Float, val z: Float)

    fun clear() {
        entities.clear()
        history.clear()
    }

    /**
     * The position [ageTicks] ticks ago, or null when the entity is unknown or
     * has not been around long enough. Null is the honest answer here: a
     * lookback with nothing behind it must not silently mean "stay put".
     */
    fun positionAgo(runtimeId: Long, ageTicks: Long): Sample? {
        if (ageTicks <= 0L) return null
        val ring = history[runtimeId] ?: return null
        val target = tick - ageTicks
        // Newest first: the first sample at or before the target is the answer.
        for (s in ring.asReversed()) {
            if (s.tick <= target) return s
        }
        return null
    }

    private fun record(runtimeId: Long, x: Float, y: Float, z: Float) {
        val ring = history.getOrPut(runtimeId) { ArrayDeque() }
        ring.addLast(Sample(tick, x, y, z))
        while (ring.size > HISTORY) ring.removeFirst()
    }

    /** Advance the logical clock used for last-seen eviction. */
    fun onTick() {
        tick++
    }

    /**
     * Feed one packet. Returns true when [raw] was a packet this table
     * understands and it was applied. Unknown ids are ignored, not an error:
     * the table only cares about four packets out of 244.
     */
    fun observe(raw: ByteArray, selfRuntimeId: Long = NO_SELF): Boolean {
        val id = ModuleHeader.id(raw) ?: return false
        return when (id) {
            ID_ADD_ENTITY -> applyAddEntity(raw, selfRuntimeId)
            ID_ADD_PLAYER -> applyAddPlayer(raw, selfRuntimeId)
            ID_REMOVE_ENTITY -> applyRemove(raw)
            ID_MOVE_PLAYER -> applyMove(raw, selfRuntimeId)
            else -> false
        }
    }

    // ------------------------------------------------------------------ spawn

    private fun applyAddEntity(raw: ByteArray, selfRuntimeId: Long): Boolean {
        val c = Reader(raw)
        c.skipHeader()
        val uniqueId = c.varLong() ?: return false
        val runtimeId = c.varLong() ?: return false
        val type = c.varString() ?: return false
        val x = c.f32() ?: return false
        val y = c.f32() ?: return false
        val z = c.f32() ?: return false
        c.skipFloats(3) // velocity
        val pitch = c.f32() ?: return false
        val yaw = c.f32() ?: return false
        val headYaw = c.f32() ?: return false
        if (runtimeId == selfRuntimeId) return false
        put(Entity(runtimeId, type, null, false, x, y, z, pitch, yaw, headYaw, tick))
        return true
    }

    private fun applyAddPlayer(raw: ByteArray, selfRuntimeId: Long): Boolean {
        val c = Reader(raw)
        c.skipHeader()
        c.skip(16) // uuid
        val name = c.varString()
        val runtimeId = c.varLong() ?: return false
        c.varString() // platform_chat_id
        val x = c.f32() ?: return false
        val y = c.f32() ?: return false
        val z = c.f32() ?: return false
        c.skipFloats(3) // velocity
        val pitch = c.f32() ?: return false
        val yaw = c.f32() ?: return false
        val headYaw = c.f32() ?: return false
        if (runtimeId == selfRuntimeId) return false
        put(Entity(runtimeId, TYPE_PLAYER, name, true, x, y, z, pitch, yaw, headYaw, tick))
        return true
    }

    private fun applyRemove(raw: ByteArray): Boolean {
        val c = Reader(raw)
        c.skipHeader()
        val runtimeId = c.varLong() ?: return false
        entities.remove(runtimeId)
        return true
    }

    // ------------------------------------------------------------------- move

    private fun applyMove(raw: ByteArray, selfRuntimeId: Long): Boolean {
        val c = Reader(raw)
        c.skipHeader()
        val runtimeId = c.varLong() ?: return false
        if (runtimeId == selfRuntimeId) return false
        val x = c.f32() ?: return false
        val y = c.f32() ?: return false
        val z = c.f32() ?: return false
        val pitch = c.f32() ?: return false
        val yaw = c.f32() ?: return false
        val headYaw = c.f32() ?: return false
        val existing = entities[runtimeId]
        if (existing == null) {
            // A move for an entity we never saw spawn (level chunk join, relay
            // started mid-session). Record it as unknown rather than dropping
            // the only position we will ever get for it.
            put(Entity(runtimeId, TYPE_UNKNOWN, null, false, x, y, z, pitch, yaw, headYaw, tick))
            return true
        }
        entities[runtimeId] = existing.copy(
            x = x, y = y, z = z, pitch = pitch, yaw = yaw, headYaw = headYaw, lastSeenTick = tick,
        )
        // A move is the only way a position changes, so this is where a
        // lookback sample comes from -- put() covers spawns and late joins.
        record(runtimeId, x, y, z)
        return true
    }

    private fun put(e: Entity) {
        entities.remove(e.runtimeId)
        entities[e.runtimeId] = e
        record(e.runtimeId, e.x, e.y, e.z)
        while (entities.size > maxEntities) {
            val oldest = entities.entries.minByOrNull { it.value.lastSeenTick } ?: return
            entities.remove(oldest.key)
            // History is dropped with its entity: a bounded table must not keep
            // rings for entities it has already forgotten.
            history.remove(oldest.key)
        }
    }

    /** Entities within [radius] of (x,y,z), nearest first. */
    fun near(x: Float, y: Float, z: Float, radius: Float): List<Entity> {
        val r2 = radius * radius
        return entities.values
            .filter { e ->
                val dx = e.x - x
                val dy = e.y - y
                val dz = e.z - z
                dx * dx + dy * dy + dz * dz <= r2
            }
            .sortedBy { e ->
                val dx = e.x - x
                val dy = e.y - y
                val dz = e.z - z
                dx * dx + dy * dy + dz * dz
            }
    }

    /** Nearest entity to (x,y,z) within [radius], excluding [excludeId]. */
    fun nearest(x: Float, y: Float, z: Float, radius: Float, excludeId: Long = NO_SELF): Entity? =
        near(x, y, z, radius).firstOrNull { it.runtimeId != excludeId }

    private class Reader(private val b: ByteArray) {
        private var p = 0

        fun skipHeader() {
            p += varUIntSize(b, 0) ?: 0
        }

        /** Skip [count] little-endian floats. Named so a byte count is never
         *  passed where a float count is meant. */
        /** Skip [n] raw bytes. */
        fun skip(n: Int) {
            p += n
        }

        /** Skip [count] little-endian floats. Named so a byte count is never
         *  passed where a float count is meant. */
        fun skipFloats(count: Int) {
            p += count * 4
        }

        fun f32(): Float? {
            if (p + 4 > b.size) return null
            val v = java.nio.ByteBuffer.wrap(b, p, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).float
            p += 4
            return v
        }

        fun varLong(): Long? {
            var result = 0L
            var shift = 0
            var i = p
            while (i < b.size && shift < 64) {
                val byte = b[i].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                i++
                if (byte and 0x80 == 0) {
                    p = i
                    return result
                }
                shift += 7
            }
            return null
        }

        fun varString(): String? {
            val len = varUInt(b, p) ?: return null
            val size = varUIntSize(b, p) ?: return null
            val start = p + size
            // len == 0 is a legal empty string (AddPlayer's platform_chat_id is
            // empty for most platforms) and MUST still advance the cursor.
            // Returning null here without advancing silently shifts every
            // following field by the length prefix.
            if (len < 0 || start + len > b.size) return null
            p = start + len
            return String(b, start, len, Charsets.UTF_8)
        }
    }

    companion object {
        const val NO_SELF = Long.MIN_VALUE
        const val MAX_ENTITIES = 2048

        /** Positions kept per entity for lookback (backtrack). */
        const val HISTORY = 20
        const val TYPE_PLAYER = "player"
        const val TYPE_UNKNOWN = "unknown"

        private const val ID_ADD_PLAYER = 0x0c
        private const val ID_ADD_ENTITY = 0x0d
        private const val ID_REMOVE_ENTITY = 0x0e
        private const val ID_MOVE_PLAYER = 0x13

        internal fun varUInt(b: ByteArray, offset: Int): Int? {
            var value = 0
            var shift = 0
            var i = offset
            while (i < b.size && shift < 28) {
                val byte = b[i].toInt() and 0xff
                value = value or ((byte and 0x7f) shl shift)
                i++
                if (byte and 0x80 == 0) return value
                shift += 7
            }
            return null
        }

        internal fun varUIntSize(b: ByteArray, offset: Int): Int? {
            var i = offset
            while (i < b.size) {
                if (b[i].toInt() and 0x80 == 0) return i - offset + 1
                i++
            }
            return null
        }
    }
}

/** Tiny header reader shared by the entity table; kept separate so it can be
 *  replaced if the packet-id table ever moves. */
internal object ModuleHeader {
    fun id(raw: ByteArray): Int? {
        if (raw.isEmpty()) return null
        val header = EntityTable.varUInt(raw, 0) ?: return null
        return header and 0x3FF
    }
}