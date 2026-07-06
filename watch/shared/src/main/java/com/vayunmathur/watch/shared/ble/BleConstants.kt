package com.vayunmathur.watch.shared.ble

import java.util.UUID

/**
 * Single source of truth for the BLE contract between the watch (GATT server) and
 * phone (GATT client). Shared by both apps so the UUIDs/opcodes cannot drift.
 */
object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("4a3b2c1d-1234-5678-1234-567812345678")

    // READ + NOTIFY: watch streams JSON batch chunks to the phone.
    val DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("4a3b2c1d-1234-5678-1234-567812345679")

    // WRITE: phone sends control opcodes (ACK / CLEAR) back to the watch.
    val CONTROL_CHARACTERISTIC_UUID: UUID = UUID.fromString("4a3b2c1d-1234-5678-1234-56781234567a")

    // READ + WRITE + NOTIFY: the current Do-Not-Disturb interruption filter as a
    // single byte (1=ALL, 2=PRIORITY, 3=NONE, 4=ALARMS). Either side can write it
    // and both get notified; the watch holds the authoritative value.
    val DND_CHARACTERISTIC_UUID: UUID = UUID.fromString("4a3b2c1d-1234-5678-1234-56781234567b")

    // Client Characteristic Configuration Descriptor (standard 0x2902).
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Control opcodes (first byte of a Control characteristic write).
    const val OPCODE_ACK: Byte = 0x01
    const val OPCODE_CLEAR: Byte = 0x02

    // End-of-transfer marker sent as its own notification after the last chunk.
    val EOT_MARKER: ByteArray = byteArrayOf(0x04)
}
