package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// ---- Peer types ----

data class PeerUser(val userId: Long) : TlObject {
    override val typeId = 0x59511722.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt64(userId) }
    companion object { fun decode(buf: TlBuffer) = PeerUser(buf.int64()) }
}

data class PeerChat(val chatId: Long) : TlObject {
    override val typeId = 0x36c6019a.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt64(chatId) }
    companion object { fun decode(buf: TlBuffer) = PeerChat(buf.int64()) }
}

data class PeerChannel(val channelId: Long) : TlObject {
    override val typeId = 0xa2a5371e.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt64(channelId) }
    companion object { fun decode(buf: TlBuffer) = PeerChannel(buf.int64()) }
}

// ---- InputPeer types ----

object InputPeerSelf : TlObject {
    override val typeId = 0x7da07ec9.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}

data class InputPeerUser(val userId: Long, val accessHash: Long) : TlObject {
    override val typeId = 0xdde8a54c.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt64(userId); buf.putInt64(accessHash) }
}

data class InputPeerChat(val chatId: Long) : TlObject {
    override val typeId = 0x35a95cb9.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt64(chatId) }
}

data class InputPeerChannel(val channelId: Long, val accessHash: Long) : TlObject {
    override val typeId = 0x27bcbbfc.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt64(channelId); buf.putInt64(accessHash) }
}

fun decodePeer(buf: TlBuffer): TlObject {
    val id = buf.int32()
    return when (id) {
        0x59511722.toInt() -> PeerUser.decode(buf)
        0x36c6019a.toInt() -> PeerChat.decode(buf)
        0xa2a5371e.toInt() -> PeerChannel.decode(buf)
        else -> throw IllegalArgumentException("Unknown peer type: 0x${id.toUInt().toString(16)}")
    }
}
