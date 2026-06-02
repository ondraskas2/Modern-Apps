package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// Simplified media types — we only need type detection for previews

object MessageMediaEmpty : TlObject {
    override val typeId = 0x3ded6320.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaPhoto(val dummy: Int = 0) : TlObject {
    override val typeId = 0x695150d7.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaDocument(val mimeType: String = "", val fileName: String = "") : TlObject {
    override val typeId = 0x4cf4d72d.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaContact(val phoneNumber: String = "", val firstName: String = "", val lastName: String = "") : TlObject {
    override val typeId = 0x70322949.toInt()
    override fun encode(buf: TlBuffer) {}
}

data class MessageMediaGeo(val dummy: Int = 0) : TlObject {
    override val typeId = 0x56e0d474.toInt()
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
    override val typeId = 0x1e287d04.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        file.encode(buf)
        buf.putId(0x1cb5c415.toInt()) // empty vector of stickers
        buf.putInt32(0)
    }
}

data class InputMediaUploadedDocument(
    val file: TlObject,
    val mimeType: String,
    val attributes: List<TlObject> = emptyList(),
) : TlObject {
    override val typeId = 0x5b38c6c1.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        file.encode(buf)
        buf.putString(mimeType)
        buf.putId(0x1cb5c415.toInt()) // vector of attributes
        buf.putInt32(attributes.size)
        for (attr in attributes) attr.encode(buf)
        buf.putId(0x1cb5c415.toInt()) // empty vector of stickers
        buf.putInt32(0)
    }
}

data class DocumentAttributeFilename(val fileName: String) : TlObject {
    override val typeId = 0x15590068.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putString(fileName) }
}

// Reaction types
data class InputMessageReactionEmoji(val emoticon: String) : TlObject {
    override val typeId = 0x8935fc73.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putString(emoticon) }
}
