package com.vayunmathur.messages.telegram.api.functions

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// updates.getState
object UpdatesGetState : TlMethod<TlObject> {
    override val typeId = 0xedd4882a.toInt()
    override fun encode(buf: TlBuffer) { buf.putId(typeId) }
}

// updates.getDifference
data class UpdatesGetDifference(val pts: Int, val date: Int, val qts: Int) : TlMethod<TlObject> {
    override val typeId = 0x19c2f763.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        buf.putInt32(pts)
        buf.putInt32(date)
        buf.putInt32(qts)
    }
}
