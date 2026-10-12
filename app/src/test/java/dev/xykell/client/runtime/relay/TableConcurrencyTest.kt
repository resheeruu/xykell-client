package dev.xykell.client.runtime.relay

import dev.xykell.client.runtime.modules.ModuleWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Concurrency tests for the tables the relay shares across threads.
 *
 * Why these exist: [EntityTable], [DeathTable] and [PlayerListTable] are written
 * from the relay driver thread while decoding, and from the main looper while
 * ticking. They used plain `LinkedHashMap` and `ArrayDeque`, where concurrent
 * mutation can corrupt the map or spin its resize loop — a failure that shows up
 * as a dropped entity or a hung relay rather than as an exception.
 *
 * These tests do not prove the tables are race-free; a stress test cannot. They
 * exist so a regression to a plain collection fails here, under contention,
 * rather than silently in production. Packets are fed through the real
 * [EntityTable.observe] entry point rather than a private one, so the test
 * exercises the same path the relay does.
 */
class TableConcurrencyTest {

    private fun header(id: Int): ByteArray = ModuleWire.writeVarUInt(id)

    private fun varLong(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            if ((x and -0x80L) == 0L) {
                out.write(x.toInt())
                return out.toByteArray()
            }
            out.write((x.toInt() and 0x7f) or 0x80)
            x = x ushr 7
        }
    }

    private fun varString(s: String): ByteArray =
        ModuleWire.writeVarUInt(s.length) + s.toByteArray()

    private fun addEntity(id: Long, type: String, x: Float): ByteArray =
        header(BedrockPacketIds.infoOf("AddEntity")!!.id) +
            varLong(id) + varLong(id) + varString(type) +
            ModuleWire.writeF32LE(x) + ModuleWire.writeF32LE(64f) + ModuleWire.writeF32LE(0f) +
            ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) +
            ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f)

    private fun moveEntity(id: Long, x: Float): ByteArray =
        header(BedrockPacketIds.infoOf("MovePlayer")!!.id) + varLong(id) +
            ModuleWire.writeF32LE(x) + ModuleWire.writeF32LE(64f) + ModuleWire.writeF32LE(0f) +
            ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) + ModuleWire.writeF32LE(0f) +
            byteArrayOf(0, 1)

    /**
     * DeathInfo (0xbd), which is why the header is two bytes: 0xbd has its high
     * bit set, so its varuint encoding is two bytes rather than one.
     */
    private fun deathInfo(cause: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xbd)
        out.write(0x01)
        val bytes = cause.toByteArray(Charsets.UTF_8)
        out.write(bytes.size)
        out.write(bytes)
        out.write(0) // trailing string array: empty is a valid packet
        return out.toByteArray()
    }

    private fun playerList(type: Int, uuidFill: Int, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header(BedrockPacketIds.infoOf("PlayerList")!!.id))
        out.write(type)
        out.write(ByteArray(16) { uuidFill.toByte() })
        out.write(varString(name))
        return out.toByteArray()
    }

    @Test
    fun `entity table survives concurrent decode and tick`() {
        val table = EntityTable(maxEntities = 4096)
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        // The driver thread: decode.
        Thread {
            try {
                start.await()
                for (i in 0 until 1500) {
                    table.observe(addEntity(i.toLong(), "minecraft:pig", i.toFloat()))
                    table.observe(moveEntity(i.toLong(), i.toFloat()))
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }.start()

        // The main looper: tick, which ages entities and bumps the clock the
        // lookback reads.
        Thread {
            try {
                start.await()
                for (i in 0 until 3000) {
                    table.onTick()
                    table.positionAgo((i % 500).toLong(), (i % 7).toLong() + 1)
                    table.near(0f, 64f, 0f, 10_000f)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }.start()

        start.countDown()
        assertTrue("threads did not finish", done.await(90, TimeUnit.SECONDS))
        assertNull(
            "concurrent decode/tick threw:\n" +
                java.io.StringWriter().also { sw -> failure.get()?.printStackTrace(java.io.PrintWriter(sw)) },
            failure.get(),
        )
        assertTrue("table exceeded its bound", table.size <= 4096)
    }

    @Test
    fun `the tick counter does not lose increments`() {
        // A lost update makes a lookback resolve to the wrong frame, which shows
        // up as "sometimes wrong" and is close to impossible to debug later.
        val table = EntityTable()
        val threads = 4
        val perThread = 5000
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                start.await()
                repeat(perThread) { table.onTick() }
                done.countDown()
            }.start()
        }
        start.countDown()
        assertTrue(done.await(90, TimeUnit.SECONDS))
        assertEquals((threads * perThread).toLong(), table.tick)
    }

    @Test
    fun `player list survives concurrent update and read`() {
        val table = PlayerListTable(maxPlayers = 4096)
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        Thread {
            try {
                start.await()
                for (i in 0 until 2000) table.observe(playerList(0, i % 200, "Player$i"))
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }.start()

        Thread {
            try {
                start.await()
                for (i in 0 until 2000) {
                    table.names()
                    table.all()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }.start()

        start.countDown()
        assertTrue(done.await(90, TimeUnit.SECONDS))
        assertNull(
            "concurrent roster access threw:\n" +
                java.io.StringWriter().also { sw -> failure.get()?.printStackTrace(java.io.PrintWriter(sw)) },
            failure.get(),
        )
    }

    @Test
    fun `death table survives concurrent append and drain`() {
        val table = DeathTable(maxDeaths = 64)
        val failure = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        Thread {
            try {
                start.await()
                for (i in 0 until 2000) {
                    table.observe(
                        raw = deathInfo("entity-$i"),
                        selfX = 0f,
                        selfY = 64f,
                        selfZ = 0f,
                        observedAtMs = i.toLong(),
                    )
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }.start()

        Thread {
            try {
                start.await()
                for (i in 0 until 2000) {
                    table.latest()
                    table.recent()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }.start()

        start.countDown()
        assertTrue(done.await(90, TimeUnit.SECONDS))
        assertNull(
            "concurrent death access threw:\n" +
                java.io.StringWriter().also { sw -> failure.get()?.printStackTrace(java.io.PrintWriter(sw)) },
            failure.get(),
        )
    }
}