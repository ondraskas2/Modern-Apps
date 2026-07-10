package com.vayunmathur.headphones.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyFramingTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun checksum_isSumMod256() {
        assertEquals(0x06, SonyFraming.checksum(bytes(0x01, 0x02, 0x03)))
        assertEquals(0x00, SonyFraming.checksum(bytes(0x80, 0x80)))
        assertEquals(0xFF, SonyFraming.checksum(bytes(0xFF)))
    }

    @Test
    fun frame_producesExpectedBytesAndEscapesPayload() {
        // dataType=DATA_MDR_NO2 (0x0e), seq=0, payload=[0x3e] (a START byte -> must escape).
        val msg = SonyMessage(DataType.DATA_MDR_NO2, 0, bytes(0x3e))
        val framed = SonyFraming.frame(msg)
        // START | 0e 00 00 00 00 01 | 3d 2e (escaped 3e) | checksum 4d | END
        val expected = bytes(
            0x3e, // START
            0x0e, 0x00, 0x00, 0x00, 0x00, 0x01,
            0x3d, 0x2e, // escaped payload 0x3e
            0x4d, // checksum
            0x3c, // END
        )
        assertArrayEquals(expected, framed)
    }

    @Test
    fun frameThenAccumulate_roundTrips() {
        val msg = SonyMessage(DataType.DATA_MDR_NO2, 1, bytes(0x10, 0x09, 0x64, 0x00))
        val framed = SonyFraming.frame(msg)
        val out = FrameAccumulator().feed(framed)
        assertEquals(1, out.size)
        assertEquals(msg, out[0])
    }

    @Test
    fun escaping_roundTripsAllSpecialBytes() {
        // Payload full of bytes that require escaping.
        val msg = SonyMessage(
            DataType.DATA_MDR_NO2,
            0,
            bytes(SonyFraming.START, SonyFraming.END, SonyFraming.ESCAPE, 0x00, 0xFF),
        )
        val framed = SonyFraming.frame(msg)
        // No raw special bytes should appear inside the frame body (between first & last byte).
        for (i in 1 until framed.size - 1) {
            val v = framed[i].toInt() and 0xFF
            if (v == SonyFraming.START || v == SonyFraming.END) {
                // Only the ESCAPE marker itself is allowed; START/END must never appear inside.
                throw AssertionError("Unescaped special byte at index $i")
            }
        }
        val out = FrameAccumulator().feed(framed)
        assertEquals(1, out.size)
        assertEquals(msg, out[0])
    }

    @Test
    fun accumulator_handlesPartialReads() {
        val msg = SonyMessage(DataType.DATA_MDR_NO2, 0, bytes(0x66, 0x17, 0x01))
        val framed = SonyFraming.frame(msg)
        val acc = FrameAccumulator()
        val mid = framed.size / 2
        assertTrue(acc.feed(framed.copyOfRange(0, mid)).isEmpty())
        val out = acc.feed(framed.copyOfRange(mid, framed.size))
        assertEquals(1, out.size)
        assertEquals(msg, out[0])
    }

    @Test
    fun accumulator_handlesMultipleFramesInOneChunk() {
        val a = SonyMessage(DataType.DATA_MDR_NO2, 0, bytes(0x11, 0x00, 0x50, 0x01))
        val b = SonyMessage(DataType.ACK, 1, ByteArray(0))
        val chunk = SonyFraming.frame(a) + SonyFraming.frame(b)
        val out = FrameAccumulator().feed(chunk)
        assertEquals(2, out.size)
        assertEquals(a, out[0])
        assertEquals(b, out[1])
    }

    @Test
    fun accumulator_ignoresStrayBytesBeforeStart() {
        val msg = SonyMessage(DataType.DATA_MDR_NO2, 0, bytes(0x56, 0x00))
        val chunk = bytes(0x00, 0xAA, 0xBB) + SonyFraming.frame(msg)
        val out = FrameAccumulator().feed(chunk)
        assertEquals(1, out.size)
        assertEquals(msg, out[0])
    }

    @Test
    fun parse_rejectsBadChecksum() {
        val msg = SonyMessage(DataType.DATA_MDR_NO2, 0, bytes(0x10, 0x09))
        val framed = SonyFraming.frame(msg).copyOf()
        // Corrupt the checksum byte (second to last, before END).
        framed[framed.size - 2] = (framed[framed.size - 2] + 1).toByte()
        val out = FrameAccumulator().feed(framed)
        assertTrue(out.isEmpty())
    }

    @Test
    fun parse_rejectsDanglingEscape() {
        // START, ESCAPE, END — escape with nothing after it.
        val bad = bytes(SonyFraming.ESCAPE)
        assertNull(SonyFraming.parse(bad))
    }

    @Test
    fun hexHelpers_roundTrip() {
        val b = bytes(0x0c, 0x00, 0xFF, 0x3e)
        assertEquals("0c00ff3e", b.toHexString())
        assertArrayEquals(b, "0c 00 ff 3e".hexToByteArray())
        assertArrayEquals(b, "0x0c00FF3E".hexToByteArray())
    }
}
