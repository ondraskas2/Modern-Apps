package com.vayunmathur.messages.telegram.mtproto.proto

import java.util.concurrent.atomic.AtomicLong

object MessageId {
    private val counter = AtomicLong(0)
    private val seen = LinkedHashSet<Long>()
    private const val MAX_SEEN = 1000

    fun generate(): Long {
        val seconds = System.currentTimeMillis() / 1000L
        val c = counter.incrementAndGet() and 0xFFFF
        return (seconds shl 32) or (c shl 2)
    }

    fun isReplay(msgId: Long): Boolean {
        synchronized(seen) {
            if (msgId in seen) return true
            seen.add(msgId)
            while (seen.size > MAX_SEEN) {
                val iter = seen.iterator()
                iter.next()
                iter.remove()
            }
            return false
        }
    }
}
