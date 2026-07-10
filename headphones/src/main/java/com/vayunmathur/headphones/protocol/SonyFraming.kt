package com.vayunmathur.headphones.protocol

/**
 * Wire framing for Sony's RFCOMM protocol.
 *
 * A message on the wire is:
 * ```
 * START | escape( dataType | seq | len[4 BE] | payload | checksum ) | END
 * ```
 * where `checksum = (dataType + seq + len bytes + payload bytes) mod 256`, `len` is the
 * payload length as a 4-byte big-endian integer, and any [START]/[END]/[ESCAPE] byte that
 * would otherwise appear inside the frame is replaced by [ESCAPE] followed by `byte & 0xEF`.
 */
object SonyFraming {
    const val START: Int = 0x3E
    const val END: Int = 0x3C
    const val ESCAPE: Int = 0x3D

    /** Escaping clears bit 4 (0x10); unescaping restores it. */
    private const val ESCAPE_CLEAR_MASK: Int = 0xEF
    private const val ESCAPE_RESTORE_MASK: Int = 0x10

    /** Sum of the given bytes, mod 256. */
    fun checksum(bytes: ByteArray): Int {
        var sum = 0
        for (b in bytes) sum += b.toInt() and 0xFF
        return sum and 0xFF
    }

    /** Builds the checksum-covered region (before escaping): dataType | seq | len | payload. */
    private fun header(message: SonyMessage): ByteArray {
        val len = message.payload.size
        val out = ByteArray(6 + len)
        out[0] = message.dataType.value.toByte()
        out[1] = message.sequenceNumber.toByte()
        out[2] = (len ushr 24 and 0xFF).toByte()
        out[3] = (len ushr 16 and 0xFF).toByte()
        out[4] = (len ushr 8 and 0xFF).toByte()
        out[5] = (len and 0xFF).toByte()
        message.payload.copyInto(out, 6)
        return out
    }

    /** Serializes a [SonyMessage] into a complete, wire-ready frame. */
    fun frame(message: SonyMessage): ByteArray {
        val body = header(message)
        val checksum = checksum(body)
        val withChecksum = body + checksum.toByte()

        val out = ArrayList<Byte>(withChecksum.size + 4)
        out.add(START.toByte())
        for (b in withChecksum) {
            val v = b.toInt() and 0xFF
            if (v == START || v == END || v == ESCAPE) {
                out.add(ESCAPE.toByte())
                out.add((v and ESCAPE_CLEAR_MASK).toByte())
            } else {
                out.add(b)
            }
        }
        out.add(END.toByte())
        return out.toByteArray()
    }

    /**
     * Parses a single unescaped frame body (the bytes between START and END, still escaped).
     * Returns null if the frame is malformed or the checksum does not match.
     */
    fun parse(escapedBody: ByteArray): SonyMessage? {
        val body = unescape(escapedBody) ?: return null
        // Need at least dataType + seq + 4 len + checksum.
        if (body.size < 7) return null

        val dataType = body[0].toInt() and 0xFF
        val seq = body[1].toInt() and 0xFF
        val len = ((body[2].toInt() and 0xFF) shl 24) or
            ((body[3].toInt() and 0xFF) shl 16) or
            ((body[4].toInt() and 0xFF) shl 8) or
            (body[5].toInt() and 0xFF)

        if (len < 0 || 6 + len + 1 != body.size) return null

        val payload = body.copyOfRange(6, 6 + len)
        val expected = body[6 + len].toInt() and 0xFF
        val actual = checksum(body.copyOfRange(0, 6 + len))
        if (expected != actual) return null

        return SonyMessage(DataType.fromValue(dataType), seq, payload)
    }

    /** Reverses escaping; returns null if a trailing dangling escape is present. */
    private fun unescape(escaped: ByteArray): ByteArray? {
        val out = ArrayList<Byte>(escaped.size)
        var i = 0
        while (i < escaped.size) {
            val v = escaped[i].toInt() and 0xFF
            if (v == ESCAPE) {
                if (i + 1 >= escaped.size) return null
                val next = escaped[i + 1].toInt() and 0xFF
                out.add((next or ESCAPE_RESTORE_MASK).toByte())
                i += 2
            } else {
                out.add(escaped[i])
                i++
            }
        }
        return out.toByteArray()
    }
}

/**
 * Reassembles complete [SonyMessage]s from arbitrarily-chunked socket reads.
 *
 * RFCOMM reads may split or merge frames, so callers feed raw bytes as they arrive and
 * drain any messages that have become complete. Bytes before the first START are dropped;
 * a partial frame is retained until its END arrives.
 */
class FrameAccumulator {
    private val buffer = ArrayList<Byte>()

    /** Feeds newly-read bytes and returns any messages completed by them (may be empty). */
    fun feed(bytes: ByteArray): List<SonyMessage> {
        val messages = ArrayList<SonyMessage>()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            when {
                v == SonyFraming.START -> {
                    buffer.clear()
                    buffer.add(b)
                }
                v == SonyFraming.END && buffer.isNotEmpty() -> {
                    // Strip the leading START; parse the escaped body.
                    val body = ByteArray(buffer.size - 1)
                    for (j in 1 until buffer.size) body[j - 1] = buffer[j]
                    SonyFraming.parse(body)?.let { messages.add(it) }
                    buffer.clear()
                }
                buffer.isNotEmpty() -> buffer.add(b)
                // else: stray byte before any START — ignore.
            }
        }
        return messages
    }

    fun reset() = buffer.clear()
}
