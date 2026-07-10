package com.vayunmathur.headphones.protocol

/**
 * Turns inbound [SonyMessage]s into typed [SonyResponse]s. Unknown or malformed messages
 * become [SonyResponse.Raw] so nothing is silently dropped (and so the Debug Log can show
 * them for on-device opcode iteration).
 */
object ResponseParsers {

    fun parse(message: SonyMessage): SonyResponse {
        if (message.dataType == DataType.ACK) {
            return SonyResponse.Ack(message.sequenceNumber)
        }
        val p = message.payload
        if (p.isEmpty()) return SonyResponse.Raw(message)

        return when (p[0].toInt() and 0xFF) {
            SonyProtocol.BATTERY_RET ->
                parseBattery(p) ?: SonyResponse.Raw(message)
            SonyProtocol.NCASM_RET, SonyProtocol.NCASM_NTFY ->
                parseNcAsm(p) ?: SonyResponse.Raw(message)
            SonyProtocol.EQ_RET, SonyProtocol.EQ_NTFY ->
                parseEq(p) ?: SonyResponse.Raw(message)
            SonyProtocol.CONFIG_NTFY ->
                parseConfig(p) ?: SonyResponse.Raw(message)
            SonyProtocol.PAIRED_LIST_NTFY ->
                parsePairedDevices(p) ?: SonyResponse.Raw(message)
            SonyProtocol.CONNECT_RET_CAPABILITY_INFO ->
                SonyResponse.Capabilities(parseCapabilities(p))
            else -> SonyResponse.Raw(message)
        }
    }

    /**
     * `39 02 <count> { mac(17 ascii) flag info×3 nameLen name } terminator`.
     * flag 0x02 = currently connected, else registered/idle.
     */
    private fun parsePairedDevices(p: ByteArray): SonyResponse.PairedDevices? {
        if (p.size < 3) return null
        val count = u(p[2])
        var i = 3
        val devices = ArrayList<PairedDevice>(count)
        repeat(count) {
            if (i + 17 + 5 > p.size) return@repeat
            val mac = String(p, i, 17, Charsets.US_ASCII); i += 17
            val flag = u(p[i]); i += 1
            i += 3 // device-type/icon info
            val nameLen = u(p[i]); i += 1
            if (i + nameLen > p.size) return@repeat
            val name = String(p, i, nameLen, Charsets.US_ASCII); i += nameLen
            devices.add(PairedDevice(address = mac, name = name, connected = flag == 0x02))
        }
        return SonyResponse.PairedDevices(devices)
    }

    /** `[0xd9, subtype, 0x00, value]`. Multipoint (subtype 0xd2) value is inverted (0 = on). */
    private fun parseConfig(p: ByteArray): SonyResponse.SimpleFeature? {
        if (p.size < 4) return null
        return when (u(p[1])) {
            SonyProtocol.CONFIG_SUBTYPE_MULTIPOINT ->
                SonyResponse.SimpleFeature(FeatureToggle(Feature.MULTIPOINT, enabled = u(p[3]) == 0))
            else -> null
        }
    }

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private fun component(level: Int, charge: Int): BatteryComponent =
        BatteryComponent(level = level.coerceIn(-1, 100), charging = charge != 0)

    /**
     * `[cmd, type, ...]`:
     * - SINGLE `0x00`: `level, charge`
     * - DUAL `0x09`: `leftLevel, leftCharge, rightLevel, rightCharge`
     * - CASE `0x0a`: `level, charge`
     */
    private fun parseBattery(p: ByteArray): SonyResponse.Battery? {
        if (p.size < 2) return null
        return when (u(p[1])) {
            SonyProtocol.BATTERY_TYPE_SINGLE -> {
                if (p.size < 4) return null
                SonyResponse.Battery(BatteryInfo(single = component(u(p[2]), u(p[3]))))
            }
            SonyProtocol.BATTERY_TYPE_DUAL -> {
                if (p.size < 6) return null
                SonyResponse.Battery(
                    BatteryInfo(
                        left = component(u(p[2]), u(p[3])),
                        right = component(u(p[4]), u(p[5])),
                    ),
                )
            }
            SonyProtocol.BATTERY_TYPE_CASE -> {
                if (p.size < 4) return null
                SonyResponse.Battery(BatteryInfo(case = component(u(p[2]), u(p[3]))))
            }
            else -> null
        }
    }

    /** `[cmd, 0x17, commit, enabled, ambientMode, focusOnVoice, level]` (WF-1000XM5, decoded). */
    private fun parseNcAsm(p: ByteArray): SonyResponse.NcAsm? {
        if (p.size < 7) return null
        val enabled = u(p[3])
        val ambientMode = u(p[4])
        val focus = u(p[5]) != 0
        val level = u(p[6])
        val ncMode = when {
            enabled == 0 -> NcAsmMode.OFF
            ambientMode == 1 -> NcAsmMode.AMBIENT_SOUND
            else -> NcAsmMode.NOISE_CANCELLING
        }
        return SonyResponse.NcAsm(
            NcAsmState(
                mode = ncMode,
                ambientLevel = level.coerceIn(0, NcAsmState.MAX_AMBIENT_LEVEL),
                voicePassthrough = focus,
            ),
        )
    }

    /** `[cmd, 0x00, presetId, numBands, clearBass, b0..b4]`. */
    private fun parseEq(p: ByteArray): SonyResponse.Eq? {
        if (p.size < 4) return null
        val preset = EqPreset.fromValue(u(p[2]))
        val numBands = u(p[3])
        if (numBands == 0 || p.size < 4 + numBands) {
            return SonyResponse.Eq(preset, EqBands.FLAT)
        }
        val clearBass = u(p[4])
        val bands = ArrayList<Int>(EqBands.BAND_COUNT)
        for (i in 0 until EqBands.BAND_COUNT) {
            val idx = 5 + i
            bands.add(if (idx < p.size) u(p[idx]) else EqBands.CENTER)
        }
        return SonyResponse.Eq(preset, EqBands(clearBass = clearBass, bands = bands))
    }

    /**
     * Deep capability parsing is model-specific and not reliably documented, so for the XM5
     * target we report the standard super-set. Refine on-device via the Debug Log if needed.
     */
    private fun parseCapabilities(@Suppress("UNUSED_PARAMETER") p: ByteArray): DeviceCapabilities =
        DeviceCapabilities(
            supportsBattery = true,
            supportsDualBattery = true,
            supportsCaseBattery = true,
            supportsNcAsm = true,
            supportsAmbientLevel = true,
            supportsWindNoiseReduction = true,
            supportsEqualizer = true,
            supportsClearBass = true,
            // Only multipoint is verified on the WF-1000XM5 so far; hide unverified toggles.
            features = setOf(Feature.MULTIPOINT),
        )
}
