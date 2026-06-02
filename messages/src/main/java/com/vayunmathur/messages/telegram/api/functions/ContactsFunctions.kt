package com.vayunmathur.messages.telegram.api.functions

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// contacts.search
data class ContactsSearch(val q: String, val limit: Int = 50) : TlMethod<TlObject> {
    override val typeId = 0x11f812d8.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putString(q); buf.putInt32(limit) }
}

// contacts.resolveUsername
data class ContactsResolveUsername(val username: String) : TlMethod<TlObject> {
    override val typeId = 0xf93ccba3.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId); buf.putString(username) }
}
