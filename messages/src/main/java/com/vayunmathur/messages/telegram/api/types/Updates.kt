package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// Update types for real-time events

data class UpdateNewMessage(val message: TlObject, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0x1f2b0afd.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateNewChannelMessage(val message: TlObject, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0x62ba04d9.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateDeleteMessages(val messages: List<Int>, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0xa20db0e5.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateEditMessage(val message: TlObject, val pts: Int, val ptsCount: Int) : TlObject {
    override val typeId = 0xe40370a3.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateReadHistoryInbox(val peer: TlObject, val maxId: Int, val pts: Int) : TlObject {
    override val typeId = 0x9c974fdf.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateReadChannelInbox(val channelId: Long, val maxId: Int) : TlObject {
    override val typeId = 0x922e6e10.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateChatTitle(val chatId: Long, val title: String) : TlObject {
    override val typeId = 0x4214f37f.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateMessageReactions(val peer: TlObject, val msgId: Int) : TlObject {
    override val typeId = 0x5e1b3cb8.toInt()
    override fun encode(buf: TlBuffer) {}
}

// Update container types
data class Updates(val updates: List<TlObject>, val users: List<TlObject>, val chats: List<TlObject>, val date: Int, val seq: Int) : TlObject {
    override val typeId = 0x74ae4240.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdatesCombined(val updates: List<TlObject>, val users: List<TlObject>, val chats: List<TlObject>, val date: Int, val seqStart: Int, val seq: Int) : TlObject {
    override val typeId = 0x725b04c3.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateShort(val update: TlObject, val date: Int) : TlObject {
    override val typeId = 0x78d4dec1.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateShortMessage(
    val id: Int, val userId: Long, val message: String, val pts: Int, val ptsCount: Int, val date: Int, val out: Boolean,
) : TlObject {
    override val typeId = 0x313bc7f8.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdateShortChatMessage(
    val id: Int, val fromId: Long, val chatId: Long, val message: String, val pts: Int, val ptsCount: Int, val date: Int, val out: Boolean,
) : TlObject {
    override val typeId = 0x4d6deea5.toInt()
    override fun encode(buf: TlBuffer) {}
}

object UpdatesTooLong : TlObject {
    override val typeId = 0xe317af7e.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class UpdatesState(val pts: Int, val qts: Int, val date: Int, val seq: Int, val unreadCount: Int) : TlObject {
    override val typeId = 0xa56c2a3e.toInt()
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer) = UpdatesState(buf.int32(), buf.int32(), buf.int32(), buf.int32(), buf.int32())
    }
}

data class UpdatesDifference(
    val newMessages: List<TlObject>,
    val newEncryptedMessages: List<TlObject>,
    val otherUpdates: List<TlObject>,
    val chats: List<TlObject>,
    val users: List<TlObject>,
    val state: UpdatesState,
) : TlObject {
    override val typeId = 0x00f49d37.toInt()
    override fun encode(buf: TlBuffer) {}
}
