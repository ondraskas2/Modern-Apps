package com.vayunmathur.headphones.protocol

/** Battery level for one component (0..100), with [charging] state. -1 level = unknown. */
data class BatteryComponent(
    val level: Int,
    val charging: Boolean,
) {
    val isKnown: Boolean get() = level in 0..100

    companion object {
        val UNKNOWN = BatteryComponent(level = -1, charging = false)
    }
}

/**
 * Battery for the earbuds. For true-wireless (XM5) [left]/[right]/[case] are populated;
 * over-ear models populate only [single].
 */
data class BatteryInfo(
    val single: BatteryComponent = BatteryComponent.UNKNOWN,
    val left: BatteryComponent = BatteryComponent.UNKNOWN,
    val right: BatteryComponent = BatteryComponent.UNKNOWN,
    val case: BatteryComponent = BatteryComponent.UNKNOWN,
)

/** Top-level noise-management mode. */
enum class NcAsmMode {
    OFF,
    NOISE_CANCELLING,
    AMBIENT_SOUND,
}

/**
 * Noise-cancelling / ambient-sound state.
 *
 * @param mode current top-level mode
 * @param ambientLevel 0..20 ambient-sound level (only meaningful in [NcAsmMode.AMBIENT_SOUND])
 * @param voicePassthrough "Focus on Voice" — let speech through in ambient mode
 * @param windNoiseReduction wind-noise reduction (NC sub-mode on supported models)
 * @param adaptive Adaptive Sound Control is driving the mode automatically
 */
data class NcAsmState(
    val mode: NcAsmMode = NcAsmMode.OFF,
    val ambientLevel: Int = 0,
    val voicePassthrough: Boolean = false,
    val windNoiseReduction: Boolean = false,
    val adaptive: Boolean = false,
) {
    companion object {
        const val MAX_AMBIENT_LEVEL = 20
    }
}

/** Built-in EQ presets for the WF-1000XM5 (ids decoded from device traffic). */
enum class EqPreset(val value: Int) {
    OFF(0x00),
    BRIGHT(0x10),
    EXCITED(0x11),
    MELLOW(0x12),
    RELAXED(0x13),
    VOCAL(0x14),
    TREBLE_BOOST(0x15),
    BASS_BOOST(0x16),
    SPEECH(0x17),
    CUSTOM(0xa0),
    USER_SETTING_1(0xa1),
    USER_SETTING_2(0xa2),
    UNKNOWN(-1);

    companion object {
        fun fromValue(value: Int): EqPreset =
            entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * A 6-value EQ curve: [clearBass] plus 5 frequency bands (400/1k/2.5k/6.3k/16kHz).
 * Each value is the raw on-wire byte, 0..[STEPS]-1, centred at [CENTER] (= flat).
 */
data class EqBands(
    val clearBass: Int = CENTER,
    val bands: List<Int> = List(BAND_COUNT) { CENTER },
) {
    init {
        require(bands.size == BAND_COUNT) { "Expected $BAND_COUNT bands, got ${bands.size}" }
    }

    companion object {
        const val BAND_COUNT = 5
        const val STEPS = 21 // 0..20 on the wire
        const val CENTER = 10 // flat
        val FLAT = EqBands()
    }
}

/** A device registered to the earbuds for multipoint. */
data class PairedDevice(
    val address: String,
    val name: String,
    val connected: Boolean,
)

/** State of a simple on/off device feature. */
data class FeatureToggle(
    val feature: Feature,
    val enabled: Boolean,
)

/** Simple boolean features exposed in Settings. */
enum class Feature {
    DSEE,
    SPEAK_TO_CHAT,
    MULTIPOINT,
    ADAPTIVE_SOUND_CONTROL,
    TOUCH_SENSOR,
}

/**
 * Which optional features a connected device reports as supported. Unsupported features are
 * hidden in the UI. Populated from the capability handshake; conservative defaults are all-off.
 */
data class DeviceCapabilities(
    val supportsBattery: Boolean = false,
    val supportsDualBattery: Boolean = false,
    val supportsCaseBattery: Boolean = false,
    val supportsNcAsm: Boolean = false,
    val supportsAmbientLevel: Boolean = false,
    val supportsWindNoiseReduction: Boolean = false,
    val supportsEqualizer: Boolean = false,
    val supportsClearBass: Boolean = false,
    val features: Set<Feature> = emptySet(),
) {
    fun supports(feature: Feature): Boolean = feature in features
}
