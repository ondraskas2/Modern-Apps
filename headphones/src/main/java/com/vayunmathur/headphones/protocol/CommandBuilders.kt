package com.vayunmathur.headphones.protocol

/**
 * Builds outbound [SonyMessage]s. Each builder returns a message with the given [sequence]
 * number (the transport rewrites it to the live rolling value before sending); the payload is
 * the command id followed by its parameters.
 */
object CommandBuilders {

    private fun command(payload: ByteArray, sequence: Int): SonyMessage =
        SonyMessage(SonyProtocol.COMMAND_DATA_TYPE, sequence, payload)

    /** Device-management commands ride the DATA_MDR_NO2 (0x0e) channel. */
    private fun command2(payload: ByteArray, sequence: Int): SonyMessage =
        SonyMessage(DataType.DATA_MDR_NO2, sequence, payload)

    /** ACK for a received message. The ACK's sequence number is the *flip* of the received one. */
    fun ack(receivedSequence: Int): SonyMessage =
        SonyMessage(DataType.ACK, 1 - receivedSequence, ByteArray(0))

    // --- Init handshake ---

    fun getProtocolInfo(sequence: Int = 0): SonyMessage =
        command(byteArrayOf(SonyProtocol.CONNECT_GET_PROTOCOL_INFO.toByte(), 0x00), sequence)

    fun getCapabilities(sequence: Int = 0): SonyMessage =
        command(byteArrayOf(SonyProtocol.CONNECT_GET_CAPABILITY_INFO.toByte(), 0x00), sequence)

    // --- Battery ---

    fun getBattery(type: Int, sequence: Int = 0): SonyMessage =
        command(byteArrayOf(SonyProtocol.BATTERY_GET.toByte(), type.toByte()), sequence)

    fun getSingleBattery(sequence: Int = 0) = getBattery(SonyProtocol.BATTERY_TYPE_SINGLE, sequence)
    fun getDualBattery(sequence: Int = 0) = getBattery(SonyProtocol.BATTERY_TYPE_DUAL, sequence)
    fun getCaseBattery(sequence: Int = 0) = getBattery(SonyProtocol.BATTERY_TYPE_CASE, sequence)

    // --- Noise cancelling / ambient sound ---

    /**
     * Sets the NC/ASM state. Real WF-1000XM5 payload (decoded from device traffic):
     * `[NCASM_SET, 0x17, 0x01, enabled, ambientMode, focusOnVoice, ambientLevel]`
     * where enabled=0 → Off, ambientMode=1 → Ambient Sound / 0 → Noise Cancelling,
     * focusOnVoice=1 lets speech through in Ambient mode, and ambientLevel is 0..20.
     */
    fun setNcAsm(state: NcAsmState, sequence: Int = 0): SonyMessage {
        val enabled = if (state.mode == NcAsmMode.OFF) 0x00 else 0x01
        val ambientMode = if (state.mode == NcAsmMode.AMBIENT_SOUND) 0x01 else 0x00
        val focus = if (state.voicePassthrough) 0x01 else 0x00
        val level = state.ambientLevel.coerceIn(0, NcAsmState.MAX_AMBIENT_LEVEL)
        return command(
            byteArrayOf(
                SonyProtocol.NCASM_SET.toByte(),
                SonyProtocol.NCASM_TYPE_XM5.toByte(),
                0x01, // commit (vs 0x00 during slider preview)
                enabled.toByte(),
                ambientMode.toByte(),
                focus.toByte(),
                level.toByte(),
            ),
            sequence,
        )
    }

    fun getNcAsm(sequence: Int = 0): SonyMessage =
        command(
            byteArrayOf(SonyProtocol.NCASM_GET.toByte(), SonyProtocol.NCASM_TYPE_XM5.toByte()),
            sequence,
        )

    // --- Equalizer ---

    fun getEq(sequence: Int = 0): SonyMessage =
        command(byteArrayOf(SonyProtocol.EQ_GET.toByte(), SonyProtocol.EQ_TYPE_PRESET.toByte()), sequence)

    /** Selects a built-in preset. Payload: `[EQ_SET, 0x00, presetId, 0x00]`. */
    fun setEqPreset(preset: EqPreset, sequence: Int = 0): SonyMessage =
        command(
            byteArrayOf(
                SonyProtocol.EQ_SET.toByte(),
                SonyProtocol.EQ_TYPE_PRESET.toByte(),
                preset.value.toByte(),
                0x00,
            ),
            sequence,
        )

    /**
     * Sets a custom EQ curve by writing the 6-band values into the "Custom" slot. Payload
     * (confirmed from device traffic):
     * `[EQ_SET, 0x00, EQ_CUSTOM_SLOT, numBands(6), clearBass, b0, b1, b2, b3, b4]`.
     * Band values are raw wire bytes 0..20 (10 = flat).
     */
    fun setEqCustom(bands: EqBands, sequence: Int = 0): SonyMessage {
        val payload = ByteArray(4 + 1 + EqBands.BAND_COUNT)
        payload[0] = SonyProtocol.EQ_SET.toByte()
        payload[1] = SonyProtocol.EQ_TYPE_PRESET.toByte()
        payload[2] = SonyProtocol.EQ_CUSTOM_SLOT.toByte()
        payload[3] = (EqBands.BAND_COUNT + 1).toByte() // clear-bass + 5 bands
        payload[4] = bands.clearBass.coerceIn(0, EqBands.STEPS - 1).toByte()
        for (i in 0 until EqBands.BAND_COUNT) {
            payload[5 + i] = bands.bands[i].coerceIn(0, EqBands.STEPS - 1).toByte()
        }
        return command(payload, sequence)
    }

    // --- Simple feature toggles (best-effort; verify on-device) ---

    /** Generic `<domain>_SET, <inquiredType>, <enabled>` toggle. */
    fun setSimpleToggle(setCommand: Int, inquiredType: Int, enabled: Boolean, sequence: Int = 0): SonyMessage =
        command(
            byteArrayOf(setCommand.toByte(), inquiredType.toByte(), if (enabled) 0x01 else 0x00),
            sequence,
        )

    /**
     * Multipoint (connect to two devices). Confirmed WF-1000XM5 payload: `[0xd8, 0xd2, 0x00, v]`
     * where v is inverted — `0x00` = multipoint on, `0x01` = off (sound-quality priority).
     */
    fun setMultipoint(enabled: Boolean, sequence: Int = 0): SonyMessage =
        command(
            byteArrayOf(
                SonyProtocol.CONFIG_SET.toByte(),
                SonyProtocol.CONFIG_SUBTYPE_MULTIPOINT.toByte(),
                0x00,
                if (enabled) 0x00 else 0x01,
            ),
            sequence,
        )

    fun setFeature(feature: Feature, enabled: Boolean, sequence: Int = 0): SonyMessage = when (feature) {
        Feature.MULTIPOINT -> setMultipoint(enabled, sequence)
        Feature.DSEE ->
            setSimpleToggle(SonyProtocol.AUDIO_SET, SonyProtocol.AUDIO_TYPE_DSEE, enabled, sequence)
        Feature.SPEAK_TO_CHAT ->
            setSimpleToggle(SonyProtocol.SYSTEM_SET, SonyProtocol.SYSTEM_TYPE_SPEAK_TO_CHAT, enabled, sequence)
        Feature.ADAPTIVE_SOUND_CONTROL ->
            setSimpleToggle(SonyProtocol.SYSTEM_SET, SonyProtocol.SYSTEM_TYPE_ASC, enabled, sequence)
        Feature.TOUCH_SENSOR ->
            setSimpleToggle(SonyProtocol.SYSTEM_SET, 0x00, enabled, sequence)
    }

    /** Subscribes to (and triggers a push of) the multipoint paired-device list. */
    fun subscribePairedDevices(sequence: Int = 0): SonyMessage =
        command2(
            byteArrayOf(SonyProtocol.PAIRED_SUBSCRIBE.toByte(), SonyProtocol.PAIRED_SUBTYPE.toByte(), 0x01),
            sequence,
        )

    /** Cancels a device's registration. Payload: `3c 02 02 <MAC as 17-char ASCII>`. */
    fun removePairedDevice(address: String, sequence: Int = 0): SonyMessage {
        val mac = address.uppercase().toByteArray(Charsets.US_ASCII)
        val payload = byteArrayOf(
            SonyProtocol.PAIRED_REMOVE.toByte(),
            SonyProtocol.PAIRED_SUBTYPE.toByte(),
            0x02,
        ) + mac
        return command2(payload, sequence)
    }

    /** Sends an arbitrary raw payload (Debug Log "send raw hex"). */
    fun raw(payload: ByteArray, dataType: DataType = SonyProtocol.COMMAND_DATA_TYPE, sequence: Int = 0): SonyMessage =
        SonyMessage(dataType, sequence, payload)
}
