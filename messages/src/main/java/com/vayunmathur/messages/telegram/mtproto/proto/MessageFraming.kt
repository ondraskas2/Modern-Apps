package com.vayunmathur.messages.telegram.mtproto.proto

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

object MessageFraming {
    const val TYPE_MSG_CONTAINER = 0x73f1f8dc.toInt()
    const val TYPE_RPC_RESULT = 0xf35c6d01.toInt()
    const val TYPE_GZIP_PACKED = 0x3072cfa1.toInt()
    const val TYPE_RPC_ERROR = 0x2144ca19.toInt()
    const val TYPE_PONG = 0x347773c5.toInt()
    const val TYPE_MSGS_ACK = 0x62d6b459.toInt()
    const val TYPE_BAD_SERVER_SALT = 0xedab447b.toInt()
    const val TYPE_NEW_SESSION = 0x9ec20908.toInt()
    const val TYPE_BAD_MSG_NOTIFICATION = 0xa7eff811.toInt()
    const val TYPE_FUTURE_SALTS = 0xae500895.toInt()
    const val TYPE_MSG_DETAILED_INFO = 0x276d3ec6.toInt()
    const val TYPE_MSG_NEW_DETAILED_INFO = 0x809db6df.toInt()

    private const val MAX_UNCOMPRESSED_SIZE = 10 * 1024 * 1024 // 10 MB
    private const val MAX_INNER_MESSAGE_SIZE = 1024 * 1024 // 1 MB

    data class InnerMessage(val msgId: Long, val seqNo: Int, val data: ByteArray)

    fun writeUnencrypted(msgId: Long, data: ByteArray): ByteArray {
        val buf = TlBuffer()
        buf.putInt64(0L) // authKeyId = 0
        buf.putInt64(msgId)
        buf.putInt32(data.size)
        buf.putRawBytes(data)
        return buf.raw
    }

    fun readUnencrypted(data: ByteArray): ByteArray {
        val buf = TlBuffer(data)
        val authKeyId = buf.int64()
        require(authKeyId == 0L) { "Expected unencrypted message" }
        val msgId = buf.int64()
        val dataLen = buf.int32()
        return buf.rawBytes(dataLen)
    }

    fun parseContainer(buf: TlBuffer): List<InnerMessage> {
        val count = buf.int32()
        val messages = mutableListOf<InnerMessage>()
        for (i in 0 until count) {
            val msgId = buf.int64()
            val seqNo = buf.int32()
            val bytes = buf.int32()
            require(bytes in 0..MAX_INNER_MESSAGE_SIZE) { "Container inner message too large: $bytes" }
            val body = buf.rawBytes(bytes)
            messages.add(InnerMessage(msgId, seqNo, body))
        }
        return messages
    }

    fun parseRpcResult(buf: TlBuffer): Pair<Long, TlBuffer> {
        val reqMsgId = buf.int64()
        return reqMsgId to TlBuffer(buf.data())
    }

    fun gunzipPacked(buf: TlBuffer): ByteArray {
        val compressed = buf.bytes()
        val gzIn = GZIPInputStream(compressed.inputStream())
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var totalRead = 0
        var n = gzIn.read(buffer)
        while (n >= 0) {
            totalRead += n
            if (totalRead >= MAX_UNCOMPRESSED_SIZE) {
                throw IllegalStateException("Decompression bomb detected: exceeds ${MAX_UNCOMPRESSED_SIZE} bytes")
            }
            out.write(buffer, 0, n)
            n = gzIn.read(buffer)
        }
        return out.toByteArray()
    }

    data class RpcError(val errorCode: Int, val errorMessage: String)

    fun parseRpcError(buf: TlBuffer): RpcError {
        val code = buf.int32()
        val msg = buf.string()
        return RpcError(code, msg)
    }

    data class Pong(val msgId: Long, val pingId: Long)

    fun parsePong(buf: TlBuffer): Pong {
        val msgId = buf.int64()
        val pingId = buf.int64()
        return Pong(msgId, pingId)
    }

    fun parseMsgsAck(buf: TlBuffer): List<Long> {
        buf.consumeId(0x1cb5c415.toInt()) // vector type id
        val count = buf.int32()
        return (0 until count).map { buf.int64() }
    }

    data class BadServerSalt(val badMsgId: Long, val badMsgSeqno: Int, val errorCode: Int, val newSalt: Long)

    fun parseBadServerSalt(buf: TlBuffer): BadServerSalt {
        val badMsgId = buf.int64()
        val badMsgSeqno = buf.int32()
        val errorCode = buf.int32()
        val newSalt = buf.int64()
        return BadServerSalt(badMsgId, badMsgSeqno, errorCode, newSalt)
    }

    data class NewSession(val firstMsgId: Long, val uniqueId: Long, val serverSalt: Long)

    fun parseNewSession(buf: TlBuffer): NewSession {
        val firstMsgId = buf.int64()
        val uniqueId = buf.int64()
        val serverSalt = buf.int64()
        return NewSession(firstMsgId, uniqueId, serverSalt)
    }

    fun writeMsgsAck(msgIds: List<Long>): ByteArray {
        val buf = TlBuffer()
        buf.putId(TYPE_MSGS_ACK)
        buf.putId(0x1cb5c415.toInt()) // vector
        buf.putInt32(msgIds.size)
        for (id in msgIds) buf.putInt64(id)
        return buf.raw
    }

    // Serializes a msg_container#73f1f8dc wrapping multiple already-framed inner messages, each as
    // (msg_id:long seqno:int bytes:int body). Used to batch several outgoing content messages into a
    // single encrypted MTProto transport packet. Ref MTProto msg_container.
    fun writeContainer(inner: List<InnerMessage>): ByteArray {
        val buf = TlBuffer()
        buf.putId(TYPE_MSG_CONTAINER)
        buf.putInt32(inner.size)
        for (msg in inner) {
            buf.putInt64(msg.msgId)
            buf.putInt32(msg.seqNo)
            buf.putInt32(msg.data.size)
            buf.putRawBytes(msg.data)
        }
        return buf.raw
    }

    fun writePingDelayDisconnect(pingId: Long, disconnectDelay: Int): ByteArray {
        val buf = TlBuffer()
        buf.putId(0xf3427b8c.toInt()) // ping_delay_disconnect
        buf.putInt64(pingId)
        buf.putInt32(disconnectDelay)
        return buf.raw
    }

    fun needsAck(seqNo: Int): Boolean = (seqNo and 0x01) != 0

    data class BadMsgNotification(val badMsgId: Long, val badMsgSeqno: Int, val errorCode: Int)

    fun parseBadMsgNotification(buf: TlBuffer): BadMsgNotification {
        val badMsgId = buf.int64()
        val badMsgSeqno = buf.int32()
        val errorCode = buf.int32()
        return BadMsgNotification(badMsgId, badMsgSeqno, errorCode)
    }

    data class FutureSalt(val validSince: Int, val validUntil: Int, val salt: Long)
    data class FutureSalts(val reqMsgId: Long, val now: Int, val salts: List<FutureSalt>)

    fun parseFutureSalts(buf: TlBuffer): FutureSalts {
        val reqMsgId = buf.int64()
        val now = buf.int32()
        val count = buf.int32()
        val salts = (0 until count).map {
            FutureSalt(buf.int32(), buf.int32(), buf.int64())
        }
        return FutureSalts(reqMsgId, now, salts)
    }

    data class MsgDetailedInfo(val msgId: Long, val answerMsgId: Long, val bytes: Int)

    fun parseMsgDetailedInfo(buf: TlBuffer): MsgDetailedInfo {
        val msgId = buf.int64()
        val answerMsgId = buf.int64()
        val bytes = buf.int32()
        return MsgDetailedInfo(msgId, answerMsgId, bytes)
    }

    data class MsgNewDetailedInfo(val answerMsgId: Long, val bytes: Int)

    fun parseMsgNewDetailedInfo(buf: TlBuffer): MsgNewDetailedInfo {
        val answerMsgId = buf.int64()
        val bytes = buf.int32()
        return MsgNewDetailedInfo(answerMsgId, bytes)
    }
}
