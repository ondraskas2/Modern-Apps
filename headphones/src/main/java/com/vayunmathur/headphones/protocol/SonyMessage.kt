package com.vayunmathur.headphones.protocol

/**
 * Sony's framed RFCOMM protocol carries a one-byte "data type" that identifies the
 * transport channel a message belongs to. Command/response payloads ride on the MDR
 * channels; ACKs use [ACK].
 *
 * Values are from the community-documented protocol (SonyHeadphonesClient /
 * HeadphonesToolbox). The XM5 uses the "v2" MDR channel ([DATA_MDR_NO2]); older
 * models use [DATA_MDR].
 */
enum class DataType(val value: Int) {
    DATA(0x00),
    ACK(0x01),
    DATA_MC_NO1(0x02),
    DATA_ICD(0x09),
    DATA_EV(0x0a),
    DATA_MDR(0x0c),
    DATA_COMMON(0x0d),
    DATA_MDR_NO2(0x0e),
    SHOT(0x10),
    SHOT_MC_NO1(0x12),
    SHOT_ICD(0x19),
    SHOT_EV(0x1a),
    SHOT_MDR(0x1c),
    SHOT_COMMON(0x1d),
    SHOT_MDR_NO2(0x1e),
    LARGE_DATA_COMMON(0x2d),
    UNKNOWN(-1);

    companion object {
        fun fromValue(value: Int): DataType =
            entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

/**
 * A single, unframed protocol message: the [dataType], a 1-bit rolling [sequenceNumber]
 * used for the ACK handshake, and the raw [payload] (the command id + its parameters).
 *
 * This is the pure representation; [SonyFraming] turns it into escaped, checksummed,
 * START/END-delimited bytes for the wire and back.
 */
data class SonyMessage(
    val dataType: DataType,
    val sequenceNumber: Int,
    val payload: ByteArray,
) {
    /** The command id, i.e. the first payload byte, or -1 for an empty payload (e.g. ACK). */
    val commandId: Int
        get() = if (payload.isNotEmpty()) payload[0].toInt() and 0xFF else -1

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SonyMessage) return false
        return dataType == other.dataType &&
            sequenceNumber == other.sequenceNumber &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = dataType.hashCode()
        result = 31 * result + sequenceNumber
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String =
        "SonyMessage(dataType=$dataType, seq=$sequenceNumber, payload=${payload.toHexString()})"
}

/** Lowercase, space-free hex rendering, e.g. `0c00000000010a`. */
fun ByteArray.toHexString(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** Parses a hex string (optionally space/`0x`-separated) into bytes; ignores non-hex chars. */
fun String.hexToByteArray(): ByteArray {
    val cleaned = replace("0x", "", ignoreCase = true)
        .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    require(cleaned.length % 2 == 0) { "Hex string must have an even number of digits" }
    return ByteArray(cleaned.length / 2) { i ->
        cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
