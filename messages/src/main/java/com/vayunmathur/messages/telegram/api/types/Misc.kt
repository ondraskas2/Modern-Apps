package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// Wrapper types returned by getDialogs, getHistory, etc.

data class MessagesDialogs(
    val dialogs: List<Dialog>,
    val messages: List<TlObject>,
    val chats: List<TlObject>,
    val users: List<TlObject>,
) : TlObject {
    override val typeId = 0x15ba6c40.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessagesDialogsSlice(
    val count: Int,
    val dialogs: List<Dialog>,
    val messages: List<TlObject>,
    val chats: List<TlObject>,
    val users: List<TlObject>,
) : TlObject {
    override val typeId = 0x71e094f3.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessagesMessages(
    val messages: List<TlObject>,
    val chats: List<TlObject>,
    val users: List<TlObject>,
) : TlObject {
    override val typeId = 0x8c718e87.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessagesMessagesSlice(
    val count: Int,
    val messages: List<TlObject>,
    val chats: List<TlObject>,
    val users: List<TlObject>,
) : TlObject {
    override val typeId = 0x3a54685e.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessagesChannelMessages(
    val count: Int,
    val messages: List<TlObject>,
    val chats: List<TlObject>,
    val users: List<TlObject>,
) : TlObject {
    override val typeId = 0xc776ba4e.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class ContactsFound(
    val myResults: List<TlObject>,
    val results: List<TlObject>,
    val chats: List<TlObject>,
    val users: List<TlObject>,
) : TlObject {
    override val typeId = 0xb3134d9d.toInt()
    override fun encode(buf: TlBuffer) {}
}
