package com.vayunmathur.messages.telegram.mtproto.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TcpTransport {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    @Volatile
    var connected = false
        private set

    suspend fun connect(address: String, port: Int) = withContext(Dispatchers.IO) {
        val sock = Socket()
        sock.connect(InetSocketAddress(address, port), 10_000)
        sock.tcpNoDelay = true
        socket = sock
        input = DataInputStream(sock.getInputStream())
        output = DataOutputStream(sock.getOutputStream())

        // Intermediate codec header
        val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xEEEEEEEE.toInt()).array()
        output!!.write(header)
        output!!.flush()
        connected = true
    }

    suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw IllegalStateException("Not connected")
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(data.size).array()
        out.write(lenBuf)
        out.write(data)
        out.flush()
    }

    suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        val inp = input ?: throw IllegalStateException("Not connected")
        val lenBuf = ByteArray(4)
        inp.readFully(lenBuf)
        val len = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
        require(len in 1..16 * 1024 * 1024) { "Invalid frame length: $len" }
        val payload = ByteArray(len)
        inp.readFully(payload)
        payload
    }

    fun close() {
        connected = false
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}
