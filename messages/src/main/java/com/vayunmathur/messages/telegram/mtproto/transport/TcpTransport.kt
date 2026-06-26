package com.vayunmathur.messages.telegram.mtproto.transport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolException(val code: Int) : Exception("Protocol error: $code") {
    companion object {
        const val AUTH_KEY_NOT_FOUND = 404
        const val TRANSPORT_FLOOD = 429
        const val WRONG_DC = 444
    }
}

class TcpTransport {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    // When non-null, the connection uses MTProto obfuscated2 transport: the init packet has already
    // been sent and all subsequent traffic (including the length prefixes) is AES-CTR streamed.
    private var codec: ObfuscatedCodec? = null

    /** Optional MTProxy / obfuscation parameters. secret is the raw proxy secret (may be null). */
    data class ObfuscationConfig(val dcId: Int = 0, val secret: ByteArray? = null)

    @Volatile
    var connected = false
        private set

    suspend fun connect(address: String, port: Int, obfuscation: ObfuscationConfig? = null) = withContext(Dispatchers.IO) {
        val sock = Socket()
        sock.connect(InetSocketAddress(address, port), 35_000)
        sock.tcpNoDelay = true
        socket = sock
        input = DataInputStream(sock.getInputStream())
        output = DataOutputStream(sock.getOutputStream())

        if (obfuscation != null) {
            // Obfuscated2: the 64-byte init packet carries the transport tag and seeds the CTR
            // streams, so the plain 0xEEEEEEEE intermediate header is NOT sent separately.
            val c = ObfuscatedCodec.create(obfuscation.dcId, obfuscation.secret)
            codec = c
            output!!.write(c.initPacket)
            output!!.flush()
        } else {
            codec = null
            // Intermediate codec header
            val header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xEEEEEEEE.toInt()).array()
            output!!.write(header)
            output!!.flush()
        }
        connected = true
    }

    suspend fun send(data: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw IllegalStateException("Not connected")
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(data.size).array()
        val c = codec
        if (c != null) {
            // CTR is a stream cipher: encrypt length prefix then payload in order.
            out.write(c.encrypt(lenBuf))
            out.write(c.encrypt(data))
        } else {
            out.write(lenBuf)
            out.write(data)
        }
        out.flush()
    }

    suspend fun receive(): ByteArray = withContext(Dispatchers.IO) {
        val inp = input ?: throw IllegalStateException("Not connected")
        val c = codec
        val lenBuf = ByteArray(4)
        inp.readFully(lenBuf)
        val lenBytes = if (c != null) c.decrypt(lenBuf) else lenBuf
        val len = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).int
        if (len < 0) {
            throw ProtocolException(-len)
        }
        require(len in 1..16 * 1024 * 1024) { "Invalid frame length: $len" }
        val payload = ByteArray(len)
        inp.readFully(payload)
        if (c != null) c.decrypt(payload) else payload
    }

    fun close() {
        connected = false
        codec = null
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}
