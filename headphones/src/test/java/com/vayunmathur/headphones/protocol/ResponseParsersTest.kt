package com.vayunmathur.headphones.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseParsersTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun mdr(vararg v: Int) = SonyMessage(DataType.DATA_MDR_NO2, 0, bytes(*v))

    @Test
    fun parsesDualBattery() {
        val r = ResponseParsers.parse(mdr(0x23, 0x09, 0x64, 0x00, 0x50, 0x01))
        assertTrue(r is SonyResponse.Battery)
        val info = (r as SonyResponse.Battery).info
        assertEquals(100, info.left.level)
        assertFalse(info.left.charging)
        assertEquals(80, info.right.level)
        assertTrue(info.right.charging)
    }

    @Test
    fun parsesSingleAndCaseBattery() {
        val single = ResponseParsers.parse(mdr(0x23, 0x00, 0x2a, 0x01)) as SonyResponse.Battery
        assertEquals(42, single.info.single.level)
        assertTrue(single.info.single.charging)

        val case = ResponseParsers.parse(mdr(0x23, 0x0a, 0x37, 0x00)) as SonyResponse.Battery
        assertEquals(55, case.info.case.level)
    }

    @Test
    fun parsesNcAsmAmbient() {
        val r = ResponseParsers.parse(mdr(0x69, 0x17, 0x01, 0x01, 0x01, 0x01, 0x0f))
        assertTrue(r is SonyResponse.NcAsm)
        val s = (r as SonyResponse.NcAsm).state
        assertEquals(NcAsmMode.AMBIENT_SOUND, s.mode)
        assertEquals(15, s.ambientLevel)
        assertTrue(s.voicePassthrough)
    }

    @Test
    fun parsesNcAsmOff() {
        val s = (ResponseParsers.parse(mdr(0x69, 0x17, 0x01, 0x00, 0x00, 0x00, 0x00)) as SonyResponse.NcAsm).state
        assertEquals(NcAsmMode.OFF, s.mode)
    }

    @Test
    fun parsesNcAsmNoiseCancelling() {
        val s = (ResponseParsers.parse(mdr(0x69, 0x17, 0x01, 0x01, 0x00, 0x00, 0x00)) as SonyResponse.NcAsm).state
        assertEquals(NcAsmMode.NOISE_CANCELLING, s.mode)
    }

    @Test
    fun parsesEqPresetAndBands() {
        // Real "Bright" preset: 59 00 10 06 09 0a 0f 11 11 13
        val r = ResponseParsers.parse(mdr(0x59, 0x00, 0x10, 0x06, 0x09, 0x0a, 0x0f, 0x11, 0x11, 0x13))
        assertTrue(r is SonyResponse.Eq)
        val eq = r as SonyResponse.Eq
        assertEquals(EqPreset.BRIGHT, eq.preset)
        assertEquals(0x09, eq.bands.clearBass)
        assertEquals(listOf(0x0a, 0x0f, 0x11, 0x11, 0x13), eq.bands.bands)
    }

    @Test
    fun parsesAck() {
        val r = ResponseParsers.parse(SonyMessage(DataType.ACK, 1, ByteArray(0)))
        assertTrue(r is SonyResponse.Ack)
        assertEquals(1, (r as SonyResponse.Ack).sequenceNumber)
    }

    @Test
    fun parsesMultipointNtfy() {
        val on = ResponseParsers.parse(mdr(0xd9, 0xd2, 0x00, 0x00))
        assertTrue(on is SonyResponse.SimpleFeature)
        val onToggle = (on as SonyResponse.SimpleFeature).toggle
        assertEquals(Feature.MULTIPOINT, onToggle.feature)
        assertTrue(onToggle.enabled)

        val off = ResponseParsers.parse(mdr(0xd9, 0xd2, 0x00, 0x01)) as SonyResponse.SimpleFeature
        assertFalse(off.toggle.enabled)
    }

    @Test
    fun parsesPairedDeviceList() {
        // Real capture: two devices — "Pixel 7 Pro" (connected) and "Pixel 9 Pro XL" (registered).
        val hex = "39020230433a43343a31333a34343a43363a4430025a420c0b" +
            "506978656c20372050726f" +
            "43303a31433a36413a38443a36373a4645015a420c0e" +
            "506978656c20392050726f20584c01"
        val msg = SonyMessage(DataType.DATA_MDR_NO2, 0, hex.hexToByteArray())
        val r = ResponseParsers.parse(msg)
        assertTrue(r is SonyResponse.PairedDevices)
        val devices = (r as SonyResponse.PairedDevices).devices
        assertEquals(2, devices.size)
        assertEquals("0C:C4:13:44:C6:D0", devices[0].address)
        assertEquals("Pixel 7 Pro", devices[0].name)
        assertTrue(devices[0].connected)
        assertEquals("C0:1C:6A:8D:67:FE", devices[1].address)
        assertEquals("Pixel 9 Pro XL", devices[1].name)
        assertFalse(devices[1].connected)
    }

    @Test
    fun unknownOpcodeBecomesRaw() {
        val r = ResponseParsers.parse(mdr(0x7f, 0x01, 0x02))
        assertTrue(r is SonyResponse.Raw)
    }

    @Test
    fun endToEnd_frameAccumulateParse() {
        val wire = SonyFraming.frame(SonyMessage(DataType.DATA_MDR, 0, bytes(0x23, 0x09, 0x5a, 0x00, 0x5a, 0x00)))
        val msgs = FrameAccumulator().feed(wire)
        assertEquals(1, msgs.size)
        val r = ResponseParsers.parse(msgs[0]) as SonyResponse.Battery
        assertEquals(90, r.info.left.level)
        assertEquals(90, r.info.right.level)
    }
}
