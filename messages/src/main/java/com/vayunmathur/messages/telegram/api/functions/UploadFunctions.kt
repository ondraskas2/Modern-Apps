package com.vayunmathur.messages.telegram.api.functions

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlMethod
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

// upload.saveFilePart
data class UploadSaveFilePart(val fileId: Long, val filePart: Int, val bytes: ByteArray) : TlMethod<TlObject> {
    override val typeId = 0xb304a621.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(fileId)
        buf.putInt32(filePart)
        buf.putBytes(bytes)
    }
}

// upload.saveBigFilePart
data class UploadSaveBigFilePart(val fileId: Long, val filePart: Int, val fileTotalParts: Int, val bytes: ByteArray) : TlMethod<TlObject> {
    override val typeId = 0xde7b673d.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt64(fileId)
        buf.putInt32(filePart)
        buf.putInt32(fileTotalParts)
        buf.putBytes(bytes)
    }
}

// upload.getFile
data class UploadGetFile(val location: TlObject, val offset: Long, val limit: Int) : TlMethod<TlObject> {
    override val typeId = 0xbe5335be.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(0) // flags
        location.encode(buf)
        buf.putInt64(offset)
        buf.putInt32(limit)
    }
}
