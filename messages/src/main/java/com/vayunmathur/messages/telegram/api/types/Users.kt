package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.Fields
import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

data class User(
    val id: Long,
    val accessHash: Long = 0,
    val firstName: String = "",
    val lastName: String = "",
    val phone: String = "",
    val username: String = "",
    val bot: Boolean = false,
    val deleted: Boolean = false,
    val premium: Boolean = false,
    val photoId: Long = 0,
    val photoDcId: Int = 0,
) : TlObject {
    override val typeId = 0x31774388.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): User {
            val flags = Fields.decode(buf)
            val flags2 = Fields.decode(buf)
            val id = buf.int64()
            val accessHash = if (flags.has(0)) buf.int64() else 0L
            val firstName = if (flags.has(1)) buf.string() else ""
            val lastName = if (flags.has(2)) buf.string() else ""
            val username = if (flags.has(3)) buf.string() else ""
            val phone = if (flags.has(4)) buf.string() else ""
            val photo = if (flags.has(5)) TlSkip.parseUserProfilePhoto(buf) else null // photo
            if (flags.has(6)) TlSkip.skipUserStatus(buf) // status
            if (flags.has(14)) buf.int32() // bot_info_version
            if (flags.has(18)) { // restriction_reason vector
                TlSkip.skipVectorBoxed(buf)
            }
            if (flags.has(19)) buf.string() // bot_inline_placeholder
            if (flags.has(22)) buf.string() // lang_code
            if (flags.has(30)) TlSkip.skipBoxedType(buf) // emoji_status
            if (flags2.has(0)) { // usernames vector
                TlSkip.skipVectorBoxed(buf)
            }
            if (flags2.has(5)) TlSkip.skipRecentStory(buf) // stories_max_id
            if (flags2.has(8)) TlSkip.skipBoxedType(buf) // color
            if (flags2.has(9)) TlSkip.skipBoxedType(buf) // profile_color
            if (flags2.has(12)) buf.int32() // bot_active_users
            if (flags2.has(14)) buf.int64() // bot_verification_icon
            if (flags2.has(15)) buf.int64() // send_paid_messages_stars
            return User(
                id = id,
                accessHash = accessHash,
                firstName = firstName,
                lastName = lastName,
                phone = phone,
                username = username,
                bot = flags.has(14),
                deleted = flags.has(13),
                premium = flags.has(28),
                photoId = photo?.photoId ?: 0L,
                photoDcId = photo?.dcId ?: 0,
            )
        }
    }
}

object UserEmpty : TlObject {
    override val typeId = 0xd3bc4b7a.toInt()
    override fun encode(buf: TlBuffer) {}

    fun decode(buf: TlBuffer): UserEmpty {
        buf.int64() // id
        return UserEmpty
    }
}

data class InputUser(val userId: Long, val accessHash: Long) : TlObject {
    override val typeId = 0xf21158c6.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putInt64(userId); buf.putInt64(accessHash) }
}

object InputUserSelf : TlObject {
    override val typeId = 0xf7c1b13f.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}
