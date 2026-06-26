package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

data class Chat(
    val id: Long,
    val title: String,
    val participantsCount: Int,
    val photoId: Long = 0,
    val photoDcId: Int = 0,
) : TlObject {
    override val typeId = 0x41cbf256.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): Chat {
            val flags = Fields.decode(buf)
            val id = buf.int64()
            val title = buf.string()
            val photo = TlSkip.parseChatPhoto(buf) // photo (mandatory)
            val participantsCount = buf.int32()
            buf.int32() // date
            buf.int32() // version
            if (flags.has(6)) TlSkip.skipBoxedType(buf) // migrated_to
            if (flags.has(14)) TlSkip.skipBoxedType(buf) // admin_rights
            if (flags.has(18)) TlSkip.skipBoxedType(buf) // default_banned_rights
            return Chat(id, title, participantsCount, photo?.photoId ?: 0L, photo?.dcId ?: 0)
        }
    }
}

data class Channel(
    val id: Long,
    val accessHash: Long = 0,
    val title: String = "",
    val username: String = "",
    val megagroup: Boolean = false,
    val forum: Boolean = false,
    val photoId: Long = 0,
    val photoDcId: Int = 0,
) : TlObject {
    override val typeId = 0x1c32b11c.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): Channel {
            val flags = Fields.decode(buf)
            val flags2 = Fields.decode(buf)
            val megagroup = flags.has(8)
            val forum = flags.has(30)
            val id = buf.int64()
            val accessHash = if (flags.has(13)) buf.int64() else 0
            val title = buf.string()
            val username = if (flags.has(6)) buf.string() else ""
            val photo = TlSkip.parseChatPhoto(buf) // photo (mandatory)
            buf.int32() // date (mandatory)
            if (flags.has(9)) { // restriction_reason vector
                TlSkip.skipVectorBoxed(buf)
            }
            if (flags.has(14)) TlSkip.skipBoxedType(buf) // admin_rights
            if (flags.has(15)) TlSkip.skipBoxedType(buf) // banned_rights
            if (flags.has(18)) TlSkip.skipBoxedType(buf) // default_banned_rights
            if (flags.has(17)) buf.int32() // participants_count
            if (flags2.has(0)) TlSkip.skipVectorBoxed(buf) // usernames
            if (flags2.has(4)) TlSkip.skipRecentStory(buf) // stories_max_id
            if (flags2.has(7)) TlSkip.skipBoxedType(buf) // color
            if (flags2.has(8)) TlSkip.skipBoxedType(buf) // profile_color
            if (flags2.has(9)) TlSkip.skipBoxedType(buf) // emoji_status
            if (flags2.has(10)) buf.int32() // level
            if (flags2.has(11)) buf.int32() // subscription_until_date
            if (flags2.has(13)) buf.int64() // bot_verification_icon
            if (flags2.has(14)) buf.int64() // send_paid_messages_stars
            if (flags2.has(18)) buf.int64() // linked_monoforum_id
            return Channel(id, accessHash, title, username, megagroup = megagroup, forum = forum,
                photoId = photo?.photoId ?: 0L, photoDcId = photo?.dcId ?: 0)
        }
    }
}

data class ChatForbidden(val id: Long, val title: String) : TlObject {
    override val typeId = 0x6592a1a7.toInt()
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer) = ChatForbidden(buf.int64(), buf.string())
    }
}

data class ChannelForbidden(val id: Long, val accessHash: Long, val title: String) : TlObject {
    override val typeId = 0x17d493d5.toInt()
    override fun encode(buf: TlBuffer) {}
    companion object {
        fun decode(buf: TlBuffer): ChannelForbidden {
            val flags = Fields.decode(buf)
            val id = buf.int64()
            val accessHash = buf.int64()
            val title = buf.string()
            if (flags.has(16)) buf.int32() // until_date
            return ChannelForbidden(id, accessHash, title)
        }
    }
}
