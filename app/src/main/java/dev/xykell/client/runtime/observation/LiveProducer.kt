package dev.xykell.client.runtime.observation

/**
 * Bounded handoff between the socket reader and the synchronous poll
 * consumer. Holds already-translated observations only (never raw
 * frames, ciphertext, or envelopes). Fixed capacity with deterministic
 * drop-oldest overflow; monotonic overflow counter. No persistence,
 * no I/O, no threads owned here (callers synchronize).
 */
class LiveProducer(private val capacity: Int = HANDOFF_CAPACITY) {

    enum class Poll { EMPTY, ITEM }

    data class PollResult(val poll: Poll, val item: Translated?)

    private val queue: ArrayDeque<Translated> = ArrayDeque()
    var overflowCount: Long = 0L
        private set

    @Synchronized
    fun offer(item: Translated) {
        while (queue.size >= capacity) {
            queue.removeFirst()
            overflowCount++
        }
        queue.addLast(item)
    }

    @Synchronized
    fun pollNext(): PollResult {
        val item = queue.removeFirstOrNull() ?: return PollResult(Poll.EMPTY, null)
        return PollResult(Poll.ITEM, item)
    }

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun clear() {
        queue.clear()
    }

    companion object {
        const val HANDOFF_CAPACITY = 64
    }
}
