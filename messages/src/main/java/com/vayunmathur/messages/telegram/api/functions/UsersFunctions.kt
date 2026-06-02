package com.vayunmathur.messages.telegram.api.functions

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// users.getUsers
data class UsersGetUsers(val ids: List<TlObject>) : TlMethod<TlObject> {
    override val typeId = 0x0d91a548.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putId(0x1cb5c415.toInt())
        buf.putInt32(ids.size)
        for (id in ids) id.encode(buf)
    }
}
