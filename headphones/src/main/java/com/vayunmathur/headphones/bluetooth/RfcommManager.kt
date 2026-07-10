package com.vayunmathur.headphones.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.util.Log
import com.vayunmathur.headphones.protocol.CommandBuilders
import com.vayunmathur.headphones.protocol.DataType
import com.vayunmathur.headphones.protocol.FrameAccumulator
import com.vayunmathur.headphones.protocol.ResponseParsers
import com.vayunmathur.headphones.protocol.SonyFraming
import com.vayunmathur.headphones.protocol.SonyMessage
import com.vayunmathur.headphones.protocol.SonyResponse
import com.vayunmathur.headphones.protocol.toHexString
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class ConnectionState { Disconnected, Connecting, Connected }

/**
 * Owns a single Bluetooth Classic RFCOMM connection to the earbuds and exposes a Flow surface
 * mirroring [com.vayunmathur.watch.phone.ble.GattClientManager]: a [state] `StateFlow`, an
 * inbound [responses] `SharedFlow`, and a fire-and-forget [send].
 *
 * The XM5 is already OS-bonded for audio, so we never scan — the caller supplies a bonded
 * [BluetoothDevice]. A blocking read loop feeds the [FrameAccumulator]; a writer loop drains an
 * outbound queue, applying the rolling 1-bit sequence number and waiting for each ACK before the
 * next send. Received data messages are ACK'd automatically.
 */
@SuppressLint("MissingPermission")
class RfcommManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var socket: android.bluetooth.BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null

    private val outbound = Channel<SonyMessage>(Channel.UNLIMITED)
    private var outgoingSeq = 0

    @Volatile
    private var ackWaiter: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private val _state = MutableStateFlow(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private val _responses = MutableSharedFlow<SonyResponse>(extraBufferCapacity = 64)
    val responses: SharedFlow<SonyResponse> = _responses

    /** Raw TX/RX frames (hex) for the Debug Log page. */
    private val _traffic = MutableSharedFlow<TrafficEntry>(extraBufferCapacity = 128)
    val traffic: SharedFlow<TrafficEntry> = _traffic

    /** A decoded view of one wire frame for the Debug Log. */
    data class TrafficEntry(
        val outbound: Boolean,
        val dataType: String,
        val commandId: Int,
        val payloadHex: String,
        val frameHex: String,
    )

    private fun entryFor(outbound: Boolean, message: SonyMessage, frameHex: String) = TrafficEntry(
        outbound = outbound,
        dataType = message.dataType.name,
        commandId = message.commandId,
        payloadHex = message.payload.toHexString(),
        frameHex = frameHex,
    )

    /** Opens the RFCOMM socket to [device] and starts the read/write loops. */
    fun connect(device: BluetoothDevice) {
        if (_state.value != ConnectionState.Disconnected) return
        _state.value = ConnectionState.Connecting
        scope.launch {
            val opened = openSocket(device)
            if (opened == null) {
                _state.value = ConnectionState.Disconnected
                return@launch
            }
            socket = opened
            input = opened.inputStream
            output = opened.outputStream
            outgoingSeq = 0
            _state.value = ConnectionState.Connected
            startReadLoop()
            startWriteLoop()
        }
    }

    private fun openSocket(device: BluetoothDevice): android.bluetooth.BluetoothSocket? {
        // Discovery must not be running during connect, or it fails.
        try {
            android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()
        } catch (_: SecurityException) {
        }

        // Connect to the MDR control service by SDP UUID lookup, which resolves the correct
        // RFCOMM channel (it is NOT a fixed channel, and it is NOT the 96cc203e discovery UUID —
        // that is not even an RFCOMM service). Try the known MDR service UUID first, then fall
        // back to probing the device's other advertised custom services.
        connectUuid(device, MDR_UUID, secure = true)?.let { return it }
        connectUuid(device, MDR_UUID, secure = false)?.let { return it }

        val candidates = try {
            device.uuids?.mapNotNull { it?.uuid }?.filter { it !in WELL_KNOWN_UUIDS } ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        for (uuid in candidates) {
            if (uuid == MDR_UUID) continue
            connectUuid(device, uuid, secure = true)?.let { return it }
            connectUuid(device, uuid, secure = false)?.let { return it }
        }
        Log.e(TAG, "No MDR RFCOMM service could be connected")
        return null
    }

    private fun connectUuid(
        device: BluetoothDevice,
        uuid: UUID,
        secure: Boolean,
    ): android.bluetooth.BluetoothSocket? = try {
        val socket = if (secure) {
            device.createRfcommSocketToServiceRecord(uuid)
        } else {
            device.createInsecureRfcommSocketToServiceRecord(uuid)
        }
        socket.connect()
        Log.i(TAG, "Connected via ${if (secure) "secure" else "insecure"} SDP uuid=$uuid")
        socket
    } catch (e: IOException) {
        Log.w(TAG, "Connect uuid=$uuid secure=$secure failed", e)
        null
    }

    private fun startReadLoop() {
        readJob = scope.launch {
            val accumulator = FrameAccumulator()
            val buf = ByteArray(2048)
            val stream = input ?: return@launch
            Log.i(TAG, "Read loop started")
            try {
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val chunk = buf.copyOf(n)
                    Log.i(TAG, "RAW-RX $n bytes: ${chunk.toHexString()}")
                    for (message in accumulator.feed(chunk)) handleInbound(message)
                }
            } catch (e: IOException) {
                Log.w(TAG, "Read loop ended", e)
            }
            teardown()
        }
    }

    private suspend fun handleInbound(message: SonyMessage) {
        val frameHex = SonyFraming.frame(message).toHexString()
        Log.i(TAG, "RX ${message.dataType} cmd=0x%02x payload=%s".format(message.commandId, message.payload.toHexString()))
        _traffic.tryEmit(entryFor(outbound = false, message = message, frameHex = frameHex))
        if (message.dataType == DataType.ACK) {
            ackWaiter?.complete(Unit)
            return
        }
        _responses.tryEmit(ResponseParsers.parse(message))
        // ACK every non-ACK message the device sends.
        writeRaw(CommandBuilders.ack(message.sequenceNumber))
    }

    private fun startWriteLoop() {
        writeJob = scope.launch {
            for (message in outbound) {
                val waiter = kotlinx.coroutines.CompletableDeferred<Unit>()
                ackWaiter = waiter
                val toSend = message.copy(sequenceNumber = outgoingSeq)
                if (!writeRaw(toSend)) break
                // Wait for the device ACK, then flip the sequence bit. Proceed anyway on timeout.
                withTimeoutOrNull(ACK_TIMEOUT_MS) { waiter.await() }
                outgoingSeq = 1 - outgoingSeq
                ackWaiter = null
            }
        }
    }

    private fun writeRaw(message: SonyMessage): Boolean {
        val stream = output ?: return false
        return try {
            val bytes = SonyFraming.frame(message)
            stream.write(bytes)
            stream.flush()
            Log.i(TAG, "TX ${message.dataType} cmd=0x%02x payload=%s".format(message.commandId, message.payload.toHexString()))
            _traffic.tryEmit(entryFor(outbound = true, message = message, frameHex = bytes.toHexString()))
            true
        } catch (e: IOException) {
            Log.w(TAG, "Write failed", e)
            teardown()
            false
        }
    }

    /** Queues a command; the sequence number is assigned by the writer loop. */
    fun send(message: SonyMessage) {
        outbound.trySend(message)
    }

    fun disconnect() {
        scope.launch { teardown() }
    }

    private fun teardown() {
        if (_state.value == ConnectionState.Disconnected) return
        readJob?.cancel(); readJob = null
        writeJob?.cancel(); writeJob = null
        try { socket?.close() } catch (_: IOException) {}
        socket = null; input = null; output = null
        ackWaiter = null
        _state.value = ConnectionState.Disconnected
    }

    fun release() {
        teardown()
        scope.cancel()
    }

    companion object {
        private const val TAG = "RfcommManager"
        private const val ACK_TIMEOUT_MS = 1000L

        /**
         * The RFCOMM service UUID for Sony's legacy "MDR" control protocol on the WF-1000XM5,
         * discovered from the device's SDP records. This is what carries the 0x3e-framed
         * battery/NC/EQ traffic. NOTE: this is NOT `96CC203E-…`, which is only the app-discovery
         * (Companion/EIR) UUID and is not an RFCOMM service.
         */
        val MDR_UUID: UUID = UUID.fromString("956C7B26-D49A-4BA8-B03F-B17D393CB6E2")

        /** Sony's app-discovery UUID (not an RFCOMM service; kept for device matching only). */
        val SONY_UUID: UUID = UUID.fromString("96CC203E-5068-46AD-B32D-E316F5E069BA")

        /** Standard audio/utility services to skip when probing for the MDR service. */
        private val WELL_KNOWN_UUIDS: Set<UUID> = setOf(
            UUID.fromString("0000110A-0000-1000-8000-00805F9B34FB"), // A2DP source
            UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB"), // A2DP sink
            UUID.fromString("0000110C-0000-1000-8000-00805F9B34FB"), // AVRCP target
            UUID.fromString("0000110E-0000-1000-8000-00805F9B34FB"), // AVRCP
            UUID.fromString("0000111E-0000-1000-8000-00805F9B34FB"), // Handsfree
            UUID.fromString("00001108-0000-1000-8000-00805F9B34FB"), // Headset
            UUID.fromString("00001203-0000-1000-8000-00805F9B34FB"), // GenericAudio
            UUID.fromString("0000112F-0000-1000-8000-00805F9B34FB"), // PBAP
        )
    }
}
