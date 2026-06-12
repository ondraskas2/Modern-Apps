package com.vayunmathur.messages.telegram.api.functions

import com.vayunmathur.messages.telegram.api.types.MessageEntity
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// messages.getDialogs
data class MessagesGetDialogs(
    val offsetDate: Int = 0,
    val offsetId: Int = 0,
    val offsetPeer: TlObject,
    val limit: Int,
    val hash: Long = 0,
) : TlMethod<TlObject> {
    override val typeId = 0xa0f4cb4f.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        buf.putInt32(offsetDate)
        buf.putInt32(offsetId)
        offsetPeer.encode(buf)
        buf.putInt32(limit)
        buf.putInt64(hash)
    }
}

// messages.getHistory
data class MessagesGetHistory(
    val peer: TlObject,
    val offsetId: Int = 0,
    val offsetDate: Int = 0,
    val addOffset: Int = 0,
    val limit: Int,
    val maxId: Int = 0,
    val minId: Int = 0,
    val hash: Long = 0,
) : TlMethod<TlObject> {
    override val typeId = 0x4423e6c5.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        peer.encode(buf)
        buf.putInt32(offsetId)
        buf.putInt32(offsetDate)
        buf.putInt32(addOffset)
        buf.putInt32(limit)
        buf.putInt32(maxId)
        buf.putInt32(minId)
        buf.putInt64(hash)
    }
}

// messages.sendMessage with entities, replyTo, noWebpage support
data class MessagesSendMessage(
    val peer: TlObject,
    val message: String,
    val randomId: Long,
    val noWebpage: Boolean = false,
    val entities: List<MessageEntity> = emptyList(),
    val replyToMsgId: Int = 0,
) : TlMethod<TlObject> {
    override val typeId = 0x545cd15a.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        var flags = 0
        if (noWebpage) flags = flags or (1 shl 1)
        if (replyToMsgId != 0) flags = flags or (1 shl 0)
        if (entities.isNotEmpty()) flags = flags or (1 shl 3)
        buf.putInt32(flags)
        peer.encode(buf)
        if (replyToMsgId != 0) {
            buf.putId(0x73ec805d.toInt()) // inputReplyToMessage
            buf.putInt32(0) // flags
            buf.putInt32(replyToMsgId)
        }
        buf.putString(message)
        buf.putInt64(randomId)
        if (entities.isNotEmpty()) {
            buf.putId(0x1cb5c415.toInt()) // vector
            buf.putInt32(entities.size)
            for (e in entities) e.encode(buf)
        }
    }
}

// messages.sendMedia
data class MessagesSendMedia(
    val peer: TlObject,
    val media: TlObject,
    val message: String,
    val randomId: Long,
    val replyToMsgId: Int = 0,
    val entities: List<MessageEntity> = emptyList(),
) : TlMethod<TlObject> {
    override val typeId = 0x0330e77f.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        var flags = 0
        if (replyToMsgId != 0) flags = flags or (1 shl 0)
        if (entities.isNotEmpty()) flags = flags or (1 shl 3)
        buf.putInt32(flags)
        peer.encode(buf)
        if (replyToMsgId != 0) {
            buf.putId(0x73ec805d.toInt()) // inputReplyToMessage
            buf.putInt32(0) // flags
            buf.putInt32(replyToMsgId)
        }
        media.encode(buf)
        buf.putString(message)
        buf.putInt64(randomId)
        if (entities.isNotEmpty()) {
            buf.putId(0x1cb5c415.toInt()) // vector
            buf.putInt32(entities.size)
            for (e in entities) e.encode(buf)
        }
    }
}

// messages.readHistory
data class MessagesReadHistory(val peer: TlObject, val maxId: Int) : TlMethod<TlObject> {
    override val typeId = 0x0e306d3a.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        peer.encode(buf)
        buf.putInt32(maxId)
    }
}

// channels.readHistory
data class ChannelsReadHistory(val channel: TlObject, val maxId: Int) : TlMethod<TlObject> {
    override val typeId = 0xcc104937.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        channel.encode(buf)
        buf.putInt32(maxId)
    }
}

// messages.deleteHistory with controllable revoke
data class MessagesDeleteHistory(
    val peer: TlObject,
    val maxId: Int = 0,
    val justClear: Boolean = false,
    val revoke: Boolean = true,
) : TlMethod<TlObject> {
    override val typeId = 0xb08f922a.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        var flags = 0
        if (justClear) flags = flags or (1 shl 0)
        if (revoke) flags = flags or (1 shl 1)
        buf.putInt32(flags)
        peer.encode(buf)
        buf.putInt32(maxId)
    }
}

// messages.editMessage with entities support
data class MessagesEditMessage(
    val peer: TlObject,
    val id: Int,
    val message: String,
    val noWebpage: Boolean = false,
    val entities: List<MessageEntity> = emptyList(),
) : TlMethod<TlObject> {
    override val typeId = 0x51e842e1.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        var flags = 1 shl 11 // has message
        if (noWebpage) flags = flags or (1 shl 1)
        if (entities.isNotEmpty()) flags = flags or (1 shl 3)
        buf.putInt32(flags)
        peer.encode(buf)
        buf.putInt32(id)
        buf.putString(message)
        if (entities.isNotEmpty()) {
            buf.putId(0x1cb5c415.toInt()) // vector
            buf.putInt32(entities.size)
            for (e in entities) e.encode(buf)
        }
    }
}

// messages.sendReaction
data class MessagesSendReaction(
    val peer: TlObject,
    val msgId: Int,
    val reaction: List<TlObject>,
    val big: Boolean = false,
) : TlMethod<TlObject> {
    override val typeId = 0xd30d78d4.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        var flags = if (reaction.isNotEmpty()) 1 else 0 // bit 0: has reaction list
        if (big) flags = flags or (1 shl 1) // bit 1: big
        flags = flags or (1 shl 2) // bit 2: add_to_recent
        buf.putInt32(flags)
        peer.encode(buf)
        buf.putInt32(msgId)
        if (reaction.isNotEmpty()) {
            buf.putId(0x1cb5c415.toInt()) // vector
            buf.putInt32(reaction.size)
            for (r in reaction) r.encode(buf)
        }
    }
}

// messages.setTyping with configurable action
data class MessagesSetTyping(
    val peer: TlObject,
    val action: TlObject? = null,
    val topMsgId: Int = 0,
) : TlMethod<TlObject> {
    override val typeId = 0x58943ee2.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        var flags = 0
        if (topMsgId != 0) flags = flags or (1 shl 0)
        buf.putInt32(flags)
        peer.encode(buf)
        if (topMsgId != 0) buf.putInt32(topMsgId)
        (action ?: defaultTypingAction).encode(buf)
    }

    companion object {
        private val defaultTypingAction = object : TlObject {
            override val typeId = 0x16bf744e.toInt()
            override fun encode(buf: TlBuffer) { buf.putId(typeId) }
        }
    }
}

// messages.deleteMessages
data class MessagesDeleteMessages(
    val ids: List<Int>,
    val revoke: Boolean = true,
) : TlMethod<TlObject> {
    override val typeId = 0xe58e95d2.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(if (revoke) 1 else 0)
        buf.putId(0x1cb5c415.toInt()) // vector
        buf.putInt32(ids.size)
        for (id in ids) buf.putInt32(id)
    }
}

// channels.deleteMessages
data class ChannelsDeleteMessages(
    val channel: TlObject,
    val ids: List<Int>,
) : TlMethod<TlObject> {
    override val typeId = 0x84c1fd4e.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        channel.encode(buf)
        buf.putId(0x1cb5c415.toInt()) // vector
        buf.putInt32(ids.size)
        for (id in ids) buf.putInt32(id)
    }
}

// messages.editChatTitle
data class MessagesEditChatTitle(val chatId: Long, val title: String) : TlMethod<TlObject> {
    override val typeId = 0x73783ffd.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(chatId)
        buf.putString(title)
    }
}

// channels.editTitle
data class ChannelsEditTitle(val channel: TlObject, val title: String) : TlMethod<TlObject> {
    override val typeId = 0x566decd0.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        channel.encode(buf)
        buf.putString(title)
    }
}

// messages.setHistoryTTL
data class MessagesSetHistoryTTL(val peer: TlObject, val period: Int) : TlMethod<TlObject> {
    override val typeId = 0xb80e5fe4.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        peer.encode(buf)
        buf.putInt32(period)
    }
}

// account.updateNotifySettings
data class AccountUpdateNotifySettings(
    val peer: TlObject,
    val muteUntil: Int = 0,
    val silent: Boolean = false,
) : TlMethod<TlObject> {
    override val typeId = 0x84be5b93.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        // InputNotifyPeer
        buf.putId(0xb8bc5b0c.toInt()) // inputNotifyPeer
        peer.encode(buf)
        // InputPeerNotifySettings
        buf.putId(0xcacb6ae2.toInt()) // inputPeerNotifySettings
        var flags = 0
        if (silent) flags = flags or (1 shl 0)
        flags = flags or (1 shl 2) // has mute_until
        buf.putInt32(flags)
        if (silent) buf.putId(if (silent) 0xb5d70265.toInt() else 0x379637cd.toInt()) // Bool
        buf.putInt32(muteUntil)
    }
}

// account.updateNotifySettings for forum topics (Issue 21)
data class AccountUpdateNotifyForumTopic(
    val peer: TlObject,
    val topMsgId: Int,
    val muteUntil: Int = 0,
    val silent: Boolean = false,
) : TlMethod<TlObject> {
    override val typeId = 0x84be5b93.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        // InputNotifyForumTopic
        buf.putId(0x5c467992.toInt()) // inputNotifyForumTopic
        peer.encode(buf)
        buf.putInt32(topMsgId)
        // InputPeerNotifySettings
        buf.putId(0xcacb6ae2.toInt()) // inputPeerNotifySettings
        var flags = 0
        if (silent) flags = flags or (1 shl 0)
        flags = flags or (1 shl 2) // has mute_until
        buf.putInt32(flags)
        if (silent) buf.putId(if (silent) 0xb5d70265.toInt() else 0x379637cd.toInt()) // Bool
        buf.putInt32(muteUntil)
    }
}

// messages.toggleDialogPin
data class MessagesToggleDialogPin(
    val peer: TlObject,
    val pinned: Boolean,
) : TlMethod<TlObject> {
    override val typeId = 0xa731e257.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(if (pinned) 1 else 0) // flags: pinned = bit 0
        // InputDialogPeer
        buf.putId(0xfcaafeb7.toInt()) // inputDialogPeer
        peer.encode(buf)
    }
}

// channels.editBanned
data class ChannelsEditBanned(
    val channel: TlObject,
    val participant: TlObject,
    val bannedRights: Long = 0,
) : TlMethod<TlObject> {
    override val typeId = 0x96e6cd81.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        channel.encode(buf)
        participant.encode(buf)
        // ChatBannedRights
        buf.putId(0x9f120418.toInt()) // chatBannedRights
        buf.putInt32(if (bannedRights != 0L) 1 else 0) // flags: view_messages banned
        buf.putInt32(0) // until_date
    }
}

// channels.inviteToChannel
data class ChannelsInviteToChannel(
    val channel: TlObject,
    val users: List<TlObject>,
) : TlMethod<TlObject> {
    override val typeId = 0xc9e33d54.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        channel.encode(buf)
        buf.putId(0x1cb5c415.toInt()) // vector
        buf.putInt32(users.size)
        for (u in users) u.encode(buf)
    }
}

// messages.addChatUser
data class MessagesAddChatUser(
    val chatId: Long,
    val userId: TlObject,
    val fwdLimit: Int = 50,
) : TlMethod<TlObject> {
    override val typeId = 0xcbf7ee0e.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(chatId)
        userId.encode(buf)
        buf.putInt32(fwdLimit)
    }
}

// messages.deleteChatUser
data class MessagesDeleteChatUser(
    val chatId: Long,
    val userId: TlObject,
) : TlMethod<TlObject> {
    override val typeId = 0xa2185cab.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        buf.putInt64(chatId)
        userId.encode(buf)
    }
}

// upload.getFile
data class UploadGetFileRequest(val location: TlObject, val offset: Long, val limit: Int) : TlMethod<TlObject> {
    override val typeId = 0xbe5335be.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        location.encode(buf)
        buf.putInt64(offset)
        buf.putInt32(limit)
    }
}

// inputDocumentFileLocation for downloading documents
data class InputDocumentFileLocation(
    val id: Long,
    val accessHash: Long,
    val fileReference: ByteArray,
    val thumbSize: String = "",
) : TlObject {
    override val typeId = 0xbad07584.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(id)
        buf.putInt64(accessHash)
        buf.putBytes(fileReference)
        buf.putString(thumbSize)
    }
}

// inputPhotoFileLocation for downloading photos
data class InputPhotoFileLocation(
    val id: Long,
    val accessHash: Long,
    val fileReference: ByteArray,
    val thumbSize: String,
) : TlObject {
    override val typeId = 0x40181ffe.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(id)
        buf.putInt64(accessHash)
        buf.putBytes(fileReference)
        buf.putString(thumbSize)
    }
}

// GeoPoint for sending locations
data class InputGeoPoint(val lat: Double, val long: Double) : TlObject {
    override val typeId = 0x48222faf.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        buf.putDouble(lat)
        buf.putDouble(long)
    }
}

// inputMediaGeoPoint for sending locations as media
data class InputMediaGeoPoint(val geoPoint: InputGeoPoint) : TlObject {
    override val typeId = 0xf9c44144.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        geoPoint.encode(buf)
    }
}

// inputMediaGeoLive for sending live locations
data class InputMediaGeoLive(
    val geoPoint: InputGeoPoint,
    val stopped: Boolean = false,
    val period: Int = 0,
    val heading: Int = 0,
    val proximityNotificationRadius: Int = 0,
) : TlObject {
    override val typeId = 0x971fa843.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        val flags = (if (stopped) 1 else 0) or
            (if (period != 0) 1 shl 1 else 0) or
            (if (heading != 0) 1 shl 2 else 0) or
            (if (proximityNotificationRadius != 0) 1 shl 3 else 0)
        buf.putInt32(flags)
        geoPoint.encode(buf)
        if (heading != 0) buf.putInt32(heading)
        if (period != 0) buf.putInt32(period)
        if (proximityNotificationRadius != 0) buf.putInt32(proximityNotificationRadius)
    }
}

// InputUser for inviting/kicking
data class InputUser(val userId: Long, val accessHash: Long) : TlObject {
    override val typeId = 0xf21158c6.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(userId)
        buf.putInt64(accessHash)
    }
}

// InputChannel for channel operations
data class InputChannel(val channelId: Long, val accessHash: Long) : TlObject {
    override val typeId = 0xf35aec28.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(channelId)
        buf.putInt64(accessHash)
    }
}

// messages.readMentions — mark mentions as read
data class MessagesReadMentions(val peer: TlObject, val topMsgId: Int = 0) : TlMethod<TlObject> {
    override val typeId = 0x36e5bf4d.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        var flags = 0
        if (topMsgId != 0) flags = flags or (1 shl 0)
        buf.putInt32(flags)
        peer.encode(buf)
        if (topMsgId != 0) buf.putInt32(topMsgId)
    }
}

// messages.readReactions — mark reactions as read
data class MessagesReadReactions(val peer: TlObject, val topMsgId: Int = 0) : TlMethod<TlObject> {
    override val typeId = 0x54aa7f8e.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        var flags = 0
        if (topMsgId != 0) flags = flags or (1 shl 0)
        buf.putInt32(flags)
        peer.encode(buf)
        if (topMsgId != 0) buf.putInt32(topMsgId)
    }
}

// channels.leaveChannel
data class ChannelsLeaveChannel(val channel: TlObject) : TlMethod<TlObject> {
    override val typeId = 0xf836aa95.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        channel.encode(buf)
    }
}

// messages.deleteChat (for basic group chats)
data class MessagesDeleteChat(val chatId: Long) : TlMethod<TlObject> {
    override val typeId = 0x5bd0ee50.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(chatId)
    }
}

// channels.deleteChannel
data class ChannelsDeleteChannel(val channel: TlObject) : TlMethod<TlObject> {
    override val typeId = 0xc0111fe3.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        channel.encode(buf)
    }
}

// channels.deleteTopicHistory
data class ChannelsDeleteTopicHistory(val channel: TlObject, val topMsgId: Int) : TlMethod<TlObject> {
    override val typeId = 0x34435f2d.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        channel.encode(buf)
        buf.putInt32(topMsgId)
    }
}

// messages.editChatPhoto
data class MessagesEditChatPhoto(val chatId: Long, val photo: TlObject) : TlMethod<TlObject> {
    override val typeId = 0x35ddd674.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(chatId)
        photo.encode(buf)
    }
}

// channels.editPhoto
data class ChannelsEditPhoto(val channel: TlObject, val photo: TlObject) : TlMethod<TlObject> {
    override val typeId = 0xf12e57c9.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        channel.encode(buf)
        photo.encode(buf)
    }
}

// inputChatUploadedPhoto
data class InputChatUploadedPhoto(val file: TlObject) : TlObject {
    override val typeId = 0xbdcdaec0.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(1) // flags: has file (bit 0)
        file.encode(buf)
    }
}

// inputChatPhotoEmpty
object InputChatPhotoEmpty : TlObject {
    override val typeId = 0x1ca48f57.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
    }
}
