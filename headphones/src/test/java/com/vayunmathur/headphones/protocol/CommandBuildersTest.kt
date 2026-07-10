package com.vayunmathur.headphones.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandBuildersTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun ack_flipsSequenceNumberAndHasEmptyPayload() {
        val a = CommandBuilders.ack(0)
        assertEquals(DataType.ACK, a.dataType)
        assertEquals(1, a.sequenceNumber)
        assertEquals(0, a.payload.size)

        assertEquals(0, CommandBuilders.ack(1).sequenceNumber)
    }

    @Test
    fun battery_getters() {
        assertArrayEquals(bytes(0x22, 0x00), CommandBuilders.getSingleBattery().payload)
        assertArrayEquals(bytes(0x22, 0x09), CommandBuilders.getDualBattery().payload)
        assertArrayEquals(bytes(0x22, 0x0a), CommandBuilders.getCaseBattery().payload)
    }

    @Test
    fun setNcAsm_off() {
        val msg = CommandBuilders.setNcAsm(NcAsmState(mode = NcAsmMode.OFF))
        assertEquals(SonyProtocol.COMMAND_DATA_TYPE, msg.dataType)
        assertArrayEquals(bytes(0x68, 0x17, 0x01, 0x00, 0x00, 0x00, 0x00), msg.payload)
    }

    @Test
    fun setNcAsm_ambientWithLevel() {
        val msg = CommandBuilders.setNcAsm(
            NcAsmState(mode = NcAsmMode.AMBIENT_SOUND, ambientLevel = 15),
        )
        assertArrayEquals(bytes(0x68, 0x17, 0x01, 0x01, 0x01, 0x00, 0x0f), msg.payload)
    }

    @Test
    fun setNcAsm_ambientWithVoicePassthrough() {
        val msg = CommandBuilders.setNcAsm(
            NcAsmState(mode = NcAsmMode.AMBIENT_SOUND, ambientLevel = 5, voicePassthrough = true),
        )
        assertArrayEquals(bytes(0x68, 0x17, 0x01, 0x01, 0x01, 0x01, 0x05), msg.payload)
    }

    @Test
    fun setNcAsm_noiseCancelling() {
        val msg = CommandBuilders.setNcAsm(NcAsmState(mode = NcAsmMode.NOISE_CANCELLING))
        assertArrayEquals(bytes(0x68, 0x17, 0x01, 0x01, 0x00, 0x00, 0x00), msg.payload)
    }

    @Test
    fun setNcAsm_clampsAmbientLevel() {
        val msg = CommandBuilders.setNcAsm(NcAsmState(mode = NcAsmMode.AMBIENT_SOUND, ambientLevel = 99))
        assertEquals(NcAsmState.MAX_AMBIENT_LEVEL, msg.payload[6].toInt() and 0xFF)
    }

    @Test
    fun setEqPreset() {
        assertArrayEquals(bytes(0x58, 0x00, 0x10, 0x00), CommandBuilders.setEqPreset(EqPreset.BRIGHT).payload)
    }

    @Test
    fun setEqCustom() {
        val bands = EqBands(clearBass = 12, bands = listOf(8, 9, 10, 11, 12))
        val msg = CommandBuilders.setEqCustom(bands)
        assertArrayEquals(
            bytes(0x58, 0x00, 0xa1, 0x06, 12, 8, 9, 10, 11, 12),
            msg.payload,
        )
    }

    @Test
    fun setMultipoint() {
        // Inverted: on = 0x00, off = 0x01.
        assertArrayEquals(bytes(0xd8, 0xd2, 0x00, 0x00), CommandBuilders.setFeature(Feature.MULTIPOINT, true).payload)
        assertArrayEquals(bytes(0xd8, 0xd2, 0x00, 0x01), CommandBuilders.setFeature(Feature.MULTIPOINT, false).payload)
    }

    @Test
    fun setFeature_dsee() {
        assertArrayEquals(bytes(0xe8, 0x02, 0x01), CommandBuilders.setFeature(Feature.DSEE, true).payload)
        assertArrayEquals(bytes(0xe8, 0x02, 0x00), CommandBuilders.setFeature(Feature.DSEE, false).payload)
    }

    @Test
    fun removePairedDevice() {
        val msg = CommandBuilders.removePairedDevice("C0:1C:6A:8D:67:FE")
        assertEquals(DataType.DATA_MDR_NO2, msg.dataType)
        val expected = bytes(0x3c, 0x02, 0x02) + "C0:1C:6A:8D:67:FE".toByteArray(Charsets.US_ASCII)
        assertArrayEquals(expected, msg.payload)
    }

    @Test
    fun raw_passesPayloadThrough() {
        val payload = bytes(0xde, 0xad, 0xbe, 0xef)
        val msg = CommandBuilders.raw(payload)
        assertArrayEquals(payload, msg.payload)
    }
}
