package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

object MessageMediaEmpty : TlObject {
    override val typeId = 0x3ded6320.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaPhoto(val dummy: Int = 0) : TlObject {
    override val typeId = 0xe216eb63.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaDocument(
    val mimeType: String = "",
    val fileName: String = "",
    val isSticker: Boolean = false,
    val stickerAlt: String = "",
    val isAnimated: Boolean = false,
    val isVoice: Boolean = false,
    val isRoundVideo: Boolean = false,
    val isVideo: Boolean = false,
    val duration: Double = 0.0,
    val width: Int = 0,
    val height: Int = 0,
) : TlObject {
    override val typeId = 0x52d8ccd9.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaContact(
    val phoneNumber: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val vcard: String = "",
    val userId: Long = 0,
) : TlObject {
    override val typeId = 0x70322949.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaGeo(val lat: Double = 0.0, val long: Double = 0.0) : TlObject {
    override val typeId = 0x56e0d474.toInt()
    override fun encode(buf: TlBuffer) {}
    fun geoUri(): String = "geo:$lat,$long"
}

data class MessageMediaGeoLive(
    val lat: Double = 0.0,
    val long: Double = 0.0,
    val heading: Int = 0,
    val period: Int = 0,
    val proximityNotificationRadius: Int = 0,
) : TlObject {
    override val typeId = 0xb940c666.toInt()
    override fun encode(buf: TlBuffer) {}
    fun geoUri(): String = "geo:$lat,$long"
}

data class MessageMediaVenue(
    val lat: Double = 0.0,
    val long: Double = 0.0,
    val title: String = "",
    val address: String = "",
    val provider: String = "",
    val venueId: String = "",
    val venueType: String = "",
) : TlObject {
    override val typeId = 0x2ec0533f.toInt()
    override fun encode(buf: TlBuffer) {}
    fun geoUri(): String = "geo:$lat,$long"
}

data class MessageMediaPoll(val pollQuestion: String = "", val pollOptions: List<String> = emptyList()) : TlObject {
    override val typeId = 0x773f4e66.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaDice(val value: Int = 0, val emoticon: String = "") : TlObject {
    override val typeId = 0x08cbec07.toInt()
    override fun encode(buf: TlBuffer) {}
}

object MessageMediaUnsupported : TlObject {
    override val typeId = 0x9f84f49e.toInt()
    override fun encode(buf: TlBuffer) {}
}

// Input types for sending media
data class InputFile(val id: Long, val parts: Int, val name: String, val md5Checksum: String = "") : TlObject {
    override val typeId = 0xf52ff27f.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(id)
        buf.putInt32(parts)
        buf.putString(name)
        buf.putString(md5Checksum)
    }
}

data class InputFileBig(val id: Long, val parts: Int, val name: String) : TlObject {
    override val typeId = 0xfa4f0bb5.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(id)
        buf.putInt32(parts)
        buf.putString(name)
    }
}

data class InputMediaUploadedPhoto(val file: TlObject) : TlObject {
    override val typeId = 0x7d8375da.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        file.encode(buf)
    }
}

data class InputMediaUploadedDocument(
    val file: TlObject,
    val mimeType: String,
    val attributes: List<TlObject> = emptyList(),
) : TlObject {
    override val typeId = 0x037c9330.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        file.encode(buf)
        buf.putString(mimeType)
        buf.putId(0x1cb5c415.toInt()) // vector of attributes
        buf.putInt32(attributes.size)
        for (attr in attributes) attr.encode(buf)
    }
}

data class DocumentAttributeFilename(val fileName: String) : TlObject {
    override val typeId = 0x15590068.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putString(fileName) }
}

data class DocumentAttributeVideo(
    val duration: Double,
    val w: Int,
    val h: Int,
    val roundMessage: Boolean = false,
    val supportsStreaming: Boolean = true,
) : TlObject {
    override val typeId = 0x17399fad.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        var flags = 0
        if (roundMessage) flags = flags or (1 shl 0)
        if (supportsStreaming) flags = flags or (1 shl 1)
        buf.putInt32(flags)
        buf.putDouble(duration)
        buf.putInt32(w)
        buf.putInt32(h)
    }
}

data class DocumentAttributeAudio(
    val duration: Int,
    val voice: Boolean = false,
    val title: String = "",
    val performer: String = "",
    val waveform: ByteArray? = null,
) : TlObject {
    override val typeId = 0x9852f9c6.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        var flags = 0
        if (voice) flags = flags or (1 shl 10)
        if (title.isNotEmpty()) flags = flags or (1 shl 0)
        if (performer.isNotEmpty()) flags = flags or (1 shl 1)
        if (waveform != null) flags = flags or (1 shl 2)
        buf.putInt32(flags)
        buf.putInt32(duration)
        if (title.isNotEmpty()) buf.putString(title)
        if (performer.isNotEmpty()) buf.putString(performer)
        if (waveform != null) buf.putBytes(waveform)
    }
}

object DocumentAttributeAnimated : TlObject {
    override val typeId = 0x11b58939.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}

data class DocumentAttributeImageSize(val w: Int, val h: Int) : TlObject {
    override val typeId = 0x6c37c15c.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt32(w); buf.putInt32(h) }
}

// Web page media
data class MessageMediaWebPage(
    val url: String = "",
    val title: String = "",
    val description: String = "",
) : TlObject {
    override val typeId = 0xddf10c3b.toInt()
    override fun encode(buf: TlBuffer) {}
}

// Message entity types (for rich text formatting)
data class MessageEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String = "",
    val userId: Long = 0,
    val language: String = "",
    val documentId: Long = 0,
) : TlObject {
    override val typeId = 0
    override fun encode(buf: TlBuffer) {
        val id = when (type) {
            "bold" -> 0xbd610bc9.toInt()
            "italic" -> 0x826f8b60.toInt()
            "underline" -> 0x9c4e7e8b.toInt()
            "strikethrough" -> 0xbf0693d4.toInt()
            "code" -> 0x28a20571.toInt()
            "pre" -> 0x73924be0.toInt()
            "textUrl" -> 0x76a6d327.toInt()
            "mentionName" -> 0xdc7b1140.toInt()
            "spoiler" -> 0x32ca960f.toInt()
            "blockquote" -> 0x020df5d0.toInt()
            "customEmoji" -> 0xc8cf05f8.toInt()
            else -> return
        }
        buf.putId(id)
        buf.putInt32(offset)
        buf.putInt32(length)
        when (type) {
            "pre" -> buf.putString(language)
            "textUrl" -> buf.putString(url)
            "mentionName" -> {
                buf.putId(0xf21158c6.toInt()) // inputUser
                buf.putInt64(userId)
                buf.putInt64(0) // access_hash
            }
            "customEmoji" -> buf.putInt64(documentId)
        }
    }
}

// Forward header info
data class MessageFwdHeader(
    val fromId: TlObject? = null,
    val fromName: String = "",
    val date: Int = 0,
) : TlObject {
    override val typeId = 0x4e4df4bb.toInt()
    override fun encode(buf: TlBuffer) {}
}

// Service message action types
sealed interface MessageAction : TlObject

data class MessageActionChatEditTitle(val title: String) : MessageAction {
    override val typeId = 0xb5a1ce5a.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionChatAddUser(val users: List<Long>) : MessageAction {
    override val typeId = 0x15cefd00.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionChatDeleteUser(val userId: Long) : MessageAction {
    override val typeId = 0xa43f30cc.toInt()
    override fun encode(buf: TlBuffer) {}
}

object MessageActionChatEditPhoto : MessageAction {
    override val typeId = 0x7fcb13a8.toInt()
    override fun encode(buf: TlBuffer) {}
}

object MessageActionChatDeletePhoto : MessageAction {
    override val typeId = 0x95e3fbef.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionChatCreate(val title: String, val users: List<Long>) : MessageAction {
    override val typeId = 0xbd47cbad.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionChatMigrateTo(val channelId: Long) : MessageAction {
    override val typeId = 0xe1037f92.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionSetMessagesTTL(val period: Int) : MessageAction {
    override val typeId = 0x3c134d7b.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionPhoneCall(val video: Boolean, val duration: Int, val reason: Int) : MessageAction {
    override val typeId = 0x80e11a7f.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionChatJoinedByLink(val inviterId: Long) : MessageAction {
    override val typeId = 0x031224c3.toInt()
    override fun encode(buf: TlBuffer) {}
}

object MessageActionPinMessage : MessageAction {
    override val typeId = 0x94bd38ed.toInt()
    override fun encode(buf: TlBuffer) {}
}

object MessageActionChannelCreate : MessageAction {
    override val typeId = 0x95d2ac92.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionTopicCreate(val title: String) : MessageAction {
    override val typeId = 0x0d999256.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionGroupCall(val duration: Int) : MessageAction {
    override val typeId = 0x7a0d7f42.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionUnknown(val actionTypeId: Int) : MessageAction {
    override val typeId = actionTypeId
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionTopicEdit(val title: String = "", val iconChanged: Boolean = false) : MessageAction {
    override val typeId = 0xc0944820.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionInviteToGroupCall(val users: List<Long>) : MessageAction {
    override val typeId = 0x502f92f4.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionGroupCallScheduled(val scheduleDate: Int) : MessageAction {
    override val typeId = 0xb3a07661.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageActionChatJoinedByRequest(val dummy: Int = 0) : MessageAction {
    override val typeId = 0xebbca3cb.toInt()
    override fun encode(buf: TlBuffer) {}
}

// Reaction types
data class InputMessageReactionEmoji(val emoticon: String) : TlObject {
    override val typeId = 0x1b2286b8.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putString(emoticon) }
}

data class InputMessageReactionCustomEmoji(val documentId: Long) : TlObject {
    override val typeId = 0x8935fc73.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt64(documentId) }
}

// Typing action types
object SendMessageTypingAction : TlObject {
    override val typeId = 0x16bf744e.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}

object SendMessageCancelAction : TlObject {
    override val typeId = 0xfd5ec8f5.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}

object SendMessageRecordVideoAction : TlObject {
    override val typeId = 0xa187d66f.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}

object SendMessageRecordAudioAction : TlObject {
    override val typeId = 0xd52f73f7.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}

data class SendMessageUploadVideoAction(val progress: Int = 0) : TlObject {
    override val typeId = 0xe9763aec.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt32(progress) }
}

data class SendMessageUploadAudioAction(val progress: Int = 0) : TlObject {
    override val typeId = 0xf351d7ab.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt32(progress) }
}

data class SendMessageUploadDocumentAction(val progress: Int = 0) : TlObject {
    override val typeId = 0xaa0cd9e4.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt32(progress) }
}

data class SendMessageUploadPhotoAction(val progress: Int = 0) : TlObject {
    override val typeId = 0xd1d739de.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt32(progress) }
}

object SendMessageRecordRoundAction : TlObject {
    override val typeId = 0x88f27fbc.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}

data class SendMessageUploadRoundAction(val progress: Int = 0) : TlObject {
    override val typeId = 0x243e1c66.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt32(progress) }
}
