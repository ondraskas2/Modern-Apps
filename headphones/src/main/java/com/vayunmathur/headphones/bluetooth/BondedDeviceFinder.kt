package com.vayunmathur.headphones.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/** A bonded device that looks like a controllable Sony headphone. */
data class HeadphoneDevice(
    val name: String,
    val address: String,
)

/**
 * Finds already-bonded Sony headphones. We never scan — the XM5 is bonded by the OS for audio,
 * so we enumerate [BluetoothAdapter.bondedDevices] and keep the ones that either advertise the
 * Sony RFCOMM service UUID or have a Sony-style name.
 */
@SuppressLint("MissingPermission")
class BondedDeviceFinder(private val context: Context) {

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun findCandidates(): List<HeadphoneDevice> {
        val bonded = adapter?.bondedDevices ?: return emptyList()
        return bonded.filter { looksLikeSonyHeadphone(it) }
            .map { HeadphoneDevice(name = it.name ?: it.address, address = it.address) }
    }

    fun deviceFor(address: String): BluetoothDevice? =
        adapter?.bondedDevices?.firstOrNull { it.address == address }

    private fun looksLikeSonyHeadphone(device: BluetoothDevice): Boolean {
        val hasSonyUuid = device.uuids?.any { it.uuid == RfcommManager.SONY_UUID } == true
        if (hasSonyUuid) return true
        val name = device.name?.uppercase() ?: return false
        return SONY_NAME_HINTS.any { name.contains(it) }
    }

    private companion object {
        val SONY_NAME_HINTS = listOf("WF-1000XM", "WH-1000XM", "WF-", "WH-", "LINKBUDS", "SONY")
    }
}
