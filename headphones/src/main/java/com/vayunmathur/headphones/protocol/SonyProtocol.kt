package com.vayunmathur.headphones.protocol

/**
 * Command ids and enum-parameter values for Sony's MDR message protocol.
 *
 * The framing in [SonyFraming] is fully specified and verified. The *command ids* and their
 * parameter layouts below follow the community-reverse-engineered spec (SonyHeadphonesClient /
 * HeadphonesToolbox). The WF-1000XM5 uses the "v2" NC/ASM + EQ variants; the least-documented
 * "extras" (DSEE, Speak-to-Chat, Multipoint, ASC) are best-effort and are intended to be
 * confirmed on-device via the Debug Log page (see plan Phases 4/6).
 */
object SonyProtocol {
    /** Default transport channel for commands. The XM5 uses DATA_MDR (0x0c) — confirmed by
     * decoding real WF-1000XM5 RFCOMM traffic. (0x0e/DATA_MDR_NO2 is used only for a few
     * secondary command families.) */
    val COMMAND_DATA_TYPE = DataType.DATA_MDR

    // --- Capability / init handshake ---
    const val CONNECT_GET_PROTOCOL_INFO = 0x00
    const val CONNECT_RET_PROTOCOL_INFO = 0x01
    const val CONNECT_GET_CAPABILITY_INFO = 0x02
    const val CONNECT_RET_CAPABILITY_INFO = 0x03

    // --- Battery (COMMON domain) — WF-1000XM5 uses 0x22/0x23 ---
    const val BATTERY_GET = 0x22
    const val BATTERY_RET = 0x23
    const val BATTERY_NTFY = 0x23
    const val BATTERY_STATUS_GET = 0x12
    const val BATTERY_STATUS_RET = 0x13

    /** Battery "inquired type" selects which component a battery message refers to. */
    const val BATTERY_TYPE_SINGLE = 0x00
    const val BATTERY_TYPE_DUAL = 0x09
    const val BATTERY_TYPE_CASE = 0x0a

    // --- Noise cancelling / ambient sound (v2, XM4/XM5) ---
    const val NCASM_GET = 0x66
    const val NCASM_RET = 0x67
    const val NCASM_SET = 0x68
    const val NCASM_NTFY = 0x69

    /**
     * NC/ASM "inquired type" 0x17 = NC + Ambient + Wind + Focus-on-Voice (the XM5 super-set).
     * Payload after the type byte, per [CommandBuilders.setNcAsm]:
     * `[on/off, 0x01, mode, focusOnVoice, ambientLevel]`.
     */
    const val NCASM_TYPE_XM5 = 0x17
    const val NCASM_MODE_AMBIENT = 0x00
    const val NCASM_MODE_NC = 0x01
    const val NCASM_MODE_WIND = 0x02

    // --- Equalizer (EQEBB domain) ---
    const val EQ_GET = 0x56
    const val EQ_RET = 0x57
    const val EQ_SET = 0x58
    const val EQ_NTFY = 0x59

    /** EQ inquired type 0x00 = preset + 6-band custom curve. */
    const val EQ_TYPE_PRESET = 0x00

    /** Writable custom EQ slot ("Custom 1"), confirmed from device traffic. */
    const val EQ_CUSTOM_SLOT = 0xa1

    // --- Simple feature toggles ---
    // Multipoint lives in the "system config" domain (SET 0xd8 / NTFY 0xd9), subtype 0xd2,
    // confirmed by the device's own "MULTIPOINT_SETTING" descriptor. Its value is inverted:
    // 0x00 = multipoint on (connect 2 devices), 0x01 = off (prioritize sound quality).
    const val CONFIG_SET = 0xd8
    const val CONFIG_NTFY = 0xd9
    const val CONFIG_SUBTYPE_MULTIPOINT = 0xd2

    // --- Multipoint paired-device management (data type 0x0e) ---
    const val PAIRED_SUBSCRIBE = 0x34 // 34 02 <1=subscribe|0=unsub> -> device pushes 0x39
    const val PAIRED_LIST_NTFY = 0x39 // 39 02 <count> { mac(17) flag info3 nameLen name } 01
    const val PAIRED_REMOVE = 0x3c    // 3c 02 02 <mac ascii> : cancel a device's registration
    const val PAIRED_SUBTYPE = 0x02

    // The following simple-feature opcodes are best-effort and not yet verified for XM5.
    const val AUDIO_SET = 0xe8 // DSEE / upscaling domain (unverified for XM5)
    const val AUDIO_TYPE_DSEE = 0x02
    const val SYSTEM_SET = 0xd8 // Speak-to-Chat / ASC / misc system domain (unverified for XM5)
    const val SYSTEM_TYPE_SPEAK_TO_CHAT = 0x0c
    const val SYSTEM_TYPE_ASC = 0x03
}

/**
 * Parsed inbound messages the app understands. Anything unrecognised surfaces as [Raw] so the
 * Debug Log can show it (and so unknown opcodes can be iterated on-device).
 */
sealed interface SonyResponse {
    data class Battery(val info: BatteryInfo) : SonyResponse
    data class NcAsm(val state: NcAsmState) : SonyResponse
    data class Eq(val preset: EqPreset, val bands: EqBands) : SonyResponse
    data class Capabilities(val capabilities: DeviceCapabilities) : SonyResponse
    data class SimpleFeature(val toggle: FeatureToggle) : SonyResponse
    data class PairedDevices(val devices: List<PairedDevice>) : SonyResponse
    data class Ack(val sequenceNumber: Int) : SonyResponse
    data class Raw(val message: SonyMessage) : SonyResponse
}
