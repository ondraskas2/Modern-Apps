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
) : TlObject {
    override val typeId = 0x215c4438.toInt()
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
            // Skip remaining optional fields
            return User(
                id = id,
                accessHash = accessHash,
                firstName = firstName,
                lastName = lastName,
                phone = phone,
                username = username,
                bot = flags.has(14),
                deleted = flags.has(11),
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
