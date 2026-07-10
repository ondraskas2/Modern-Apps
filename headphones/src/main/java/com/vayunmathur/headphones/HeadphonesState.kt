package com.vayunmathur.headphones

import com.vayunmathur.headphones.bluetooth.ConnectionState
import com.vayunmathur.headphones.protocol.BatteryInfo
import com.vayunmathur.headphones.protocol.DeviceCapabilities
import com.vayunmathur.headphones.protocol.EqBands
import com.vayunmathur.headphones.protocol.EqPreset
import com.vayunmathur.headphones.protocol.Feature
import com.vayunmathur.headphones.protocol.NcAsmState
import com.vayunmathur.headphones.protocol.PairedDevice

/**
 * The single aggregated snapshot of everything the UI observes. The service folds each parsed
 * [com.vayunmathur.headphones.protocol.SonyResponse] into this and republishes it as one
 * `StateFlow`, mirroring the companion-`StateFlow` pattern from `SyncForegroundService`.
 */
data class HeadphonesState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val battery: BatteryInfo = BatteryInfo(),
    val ncAsm: NcAsmState = NcAsmState(),
    val eqPreset: EqPreset = EqPreset.OFF,
    val eqBands: EqBands = EqBands.FLAT,
    val capabilities: DeviceCapabilities = DeviceCapabilities(),
    val features: Map<Feature, Boolean> = emptyMap(),
    val pairedDevices: List<PairedDevice> = emptyList(),
) {
    val isConnected: Boolean get() = connection == ConnectionState.Connected
}
