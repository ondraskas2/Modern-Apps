package com.vayunmathur.watch.phone.ble

import java.util.UUID

/**
 * Shared BLE contract between the watch (GATT server) and phone (GATT client).
 * These UUIDs and opcodes are duplicated verbatim from the :watch:watch module.
 */
object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("4a3b2c1d-1234-5678-1234-567812345678")
    val DATA_CHARACTERISTIC_UUID: UUID = UUID.fromString("4a3b2c1d-1234-5678-1234-567812345679")
    val CONTROL_CHARACTERISTIC_UUID: UUID = UUID.fromString("4a3b2c1d-1234-5678-1234-56781234567a")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val OPCODE_ACK: Byte = 0x01
    const val OPCODE_CLEAR: Byte = 0x02

    val EOT_MARKER: ByteArray = byteArrayOf(0x04)
}
