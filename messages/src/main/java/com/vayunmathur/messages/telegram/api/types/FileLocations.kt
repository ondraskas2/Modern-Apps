package com.vayunmathur.messages.telegram.api.types

import com.vayunmathur.messages.telegram.mtproto.tl.TlBuffer
import com.vayunmathur.messages.telegram.mtproto.tl.TlObject

/** Profile/chat photo reference captured during TL decode, used to build an
 *  InputPeerPhotoFileLocation for avatar downloads. */
data class ProfilePhoto(val photoId: Long, val dcId: Int)

// ---- InputFileLocation variants (used as the `location` arg of upload.getFile) ----

// inputPeerPhotoFileLocation#37257e99 flags:# big:flags.0?true peer:InputPeer photo_id:long
data class InputPeerPhotoFileLocation(
    val peer: TlObject,
    val photoId: Long,
    val big: Boolean = false,
) : TlObject {
    override val typeId = 0x37257e99.toInt()
    override fun encode(buf: TlBuffer) {
        buf.putId(typeId)
        buf.putInt32(if (big) (1 shl 0) else 0) // flags; big is flag 0
        peer.encode(buf)
        buf.putInt64(photoId)
    }
}

// NOTE: InputPhotoFileLocation (#40181ffe) and InputDocumentFileLocation (#bad07584) already
// exist in api/functions/MessagesFunctions.kt and are reused for downloads.

// ---- upload.getFile result types ----

// upload.file#096a18d5 type:storage.FileType mtime:int bytes:bytes
data class UploadFile(
    val fileTypeId: Int,
    val mtime: Int,
    val bytes: ByteArray,
) : TlObject {
    override val typeId = 0x096a18d5.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): UploadFile {
            val fileTypeId = buf.int32() // storage.FileType constructor (parameterless)
            val mtime = buf.int32()
            val bytes = buf.bytes()
            return UploadFile(fileTypeId, mtime, bytes)
        }
    }
}

// upload.fileCdnRedirect#f18cda44 — CDN download is not implemented; we capture the
// constructor so the decoder consumes the frame and the downloader can bail out cleanly.
// UNVERIFIED: CDN-redirected files are not downloadable by this client yet.
data class UploadFileCdnRedirect(
    val dcId: Int,
) : TlObject {
    override val typeId = 0xf18cda44.toInt()
    override fun encode(buf: TlBuffer) {}

    companion object {
        fun decode(buf: TlBuffer): UploadFileCdnRedirect {
            val dcId = buf.int32()
            buf.bytes() // file_token
            buf.bytes() // encryption_key
            buf.bytes() // encryption_iv
            TlSkip.skipVectorBoxed(buf) // file_hashes: vector<FileHash>
            return UploadFileCdnRedirect(dcId)
        }
    }
}
