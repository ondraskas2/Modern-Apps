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

// updates.getChannelDifference#3173d78 flags:# force:flags.0?true channel:InputChannel
//   filter:ChannelMessagesFilter pts:int limit:int
data class UpdatesGetChannelDifference(
    val channelId: Long,
    val accessHash: Long,
    val pts: Int,
    val limit: Int = 100,
    val force: Boolean = false,
) : TlMethod<TlObject> {
    override val typeId = 0x03173d78.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(if (force) (1 shl 0) else 0) // flags; force is flag 0
        // channel: inputChannel#f35aec28 channel_id:long access_hash:long
        buf.putId(0xf35aec28.toInt())
        buf.putInt64(channelId)
        buf.putInt64(accessHash)
        // filter: channelMessagesFilterEmpty#94d42ee7
        buf.putId(0x94d42ee7.toInt())
        buf.putInt32(pts)
        buf.putInt32(limit)
    }
}
