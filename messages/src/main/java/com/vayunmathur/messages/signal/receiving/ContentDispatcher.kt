package com.vayunmathur.messages.signal.receiving

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.SignalServiceProtos

data class DecryptedMessage(
    val senderAci: String,
    val senderDeviceId: Int,
    val timestamp: Long,
    val serverTimestamp: Long,
    val content: MessageContent,
)

sealed interface MessageContent {
    data class TextMessage(val body: String, val timestamp: Long, val groupId: String? = null) : MessageContent
    data class Reaction(val emoji: String, val targetTimestamp: Long, val remove: Boolean, val groupId: String? = null) : MessageContent
    data class Delete(val targetTimestamp: Long, val groupId: String? = null) : MessageContent
    data class Edit(val targetTimestamp: Long, val newBody: String, val groupId: String? = null) : MessageContent
    data class ReadReceipt(val timestamps: List<Long>) : MessageContent
    data class DeliveryReceipt(val timestamps: List<Long>) : MessageContent
    data class Typing(val isTyping: Boolean, val groupId: String? = null) : MessageContent
    data class SyncSent(val destinationAci: String?, val message: MessageContent?, val timestamp: Long) : MessageContent
    data class SyncRead(val messages: List<Pair<String, Long>>) : MessageContent
    data class Call(val isRinging: Boolean) : MessageContent
    data class Attachment(val body: String?, val attachments: List<AttachmentPointer>, val groupId: String? = null) : MessageContent
    data class Unknown(val description: String) : MessageContent
}

data class AttachmentPointer(
    val cdnId: Long,
    val cdnKey: String,
    val contentType: String,
    val key: ByteArray,
    val digest: ByteArray,
    val size: Int,
    val fileName: String?,
)

object ContentDispatcher {

    private const val TAG = "SignalReceiver"

    fun dispatch(
        senderAci: String,
        senderDeviceId: Int,
        content: SignalServiceProtos.Content,
        timestamp: Long,
        serverTimestamp: Long,
    ): DecryptedMessage {
        val messageContent = when {
            content.hasEditMessage() -> dispatchEdit(content.editMessage)
            content.hasDataMessage() -> dispatchData(content.dataMessage, timestamp)
            content.hasSyncMessage() -> dispatchSync(content.syncMessage, timestamp)
            content.hasReceiptMessage() -> dispatchReceipt(content.receiptMessage)
            content.hasTypingMessage() -> dispatchTyping(content.typingMessage)
            content.hasCallMessage() -> dispatchCall(content.callMessage)
            else -> MessageContent.Unknown("Unrecognized content")
        }

        return DecryptedMessage(
            senderAci = senderAci,
            senderDeviceId = senderDeviceId,
            timestamp = timestamp,
            serverTimestamp = serverTimestamp,
            content = messageContent,
        )
    }

    private fun dispatchData(data: SignalServiceProtos.DataMessage, timestamp: Long): MessageContent {
        val groupId = extractGroupId(data)

        if (data.hasDelete()) {
            return MessageContent.Delete(data.delete.targetSentTimestamp, groupId)
        }

        if (data.hasReaction()) {
            val r = data.reaction
            return MessageContent.Reaction(r.emoji, r.targetSentTimestamp, r.remove, groupId)
        }

        if (data.attachmentsCount > 0) {
            val pointers = data.attachmentsList.map { a ->
                AttachmentPointer(
                    cdnId = a.cdnId,
                    cdnKey = a.cdnKey,
                    contentType = a.contentType,
                    key = a.key.toByteArray(),
                    digest = a.digest.toByteArray(),
                    size = a.size,
                    fileName = if (a.hasFileName()) a.fileName else null,
                )
            }
            return MessageContent.Attachment(
                body = if (data.hasBody()) data.body else null,
                attachments = pointers,
                groupId = groupId,
            )
        }

        if (data.hasBody()) {
            return MessageContent.TextMessage(data.body, data.timestamp, groupId)
        }

        return MessageContent.Unknown("DataMessage with no recognized fields")
    }

    private fun dispatchEdit(edit: SignalServiceProtos.EditMessage): MessageContent {
        val newBody = if (edit.hasDataMessage() && edit.dataMessage.hasBody()) {
            edit.dataMessage.body
        } else {
            ""
        }
        val groupId = if (edit.hasDataMessage()) extractGroupId(edit.dataMessage) else null
        return MessageContent.Edit(edit.targetSentTimestamp, newBody, groupId)
    }

    private fun dispatchSync(sync: SignalServiceProtos.SyncMessage, timestamp: Long): MessageContent {
        if (sync.hasSent()) {
            val sent = sync.sent
            val innerContent = if (sent.hasMessage()) dispatchData(sent.message, sent.timestamp) else null
            return MessageContent.SyncSent(
                destinationAci = if (sent.hasDestinationServiceId()) sent.destinationServiceId else null,
                message = innerContent,
                timestamp = sent.timestamp,
            )
        }

        if (sync.readCount > 0) {
            val reads = sync.readList.map { it.senderAci to it.timestamp }
            return MessageContent.SyncRead(reads)
        }

        return MessageContent.Unknown("SyncMessage with no recognized fields")
    }

    private fun dispatchReceipt(receipt: SignalServiceProtos.ReceiptMessage): MessageContent {
        val timestamps = receipt.timestampList.map { it }
        return when (receipt.type) {
            SignalServiceProtos.ReceiptMessage.Type.READ -> MessageContent.ReadReceipt(timestamps)
            else -> MessageContent.DeliveryReceipt(timestamps)
        }
    }

    private fun dispatchTyping(typing: SignalServiceProtos.TypingMessage): MessageContent {
        val isTyping = typing.action == SignalServiceProtos.TypingMessage.Action.STARTED
        val groupId = if (typing.hasGroupId()) Base64.encodeToString(typing.groupId.toByteArray(), Base64.NO_WRAP) else null
        return MessageContent.Typing(isTyping, groupId)
    }

    private fun dispatchCall(call: SignalServiceProtos.CallMessage): MessageContent {
        val isRinging = call.hasOffer()
        return MessageContent.Call(isRinging)
    }

    private fun extractGroupId(data: SignalServiceProtos.DataMessage): String? {
        if (!data.hasGroupV2()) return null
        return try {
            Base64.encodeToString(data.groupV2.masterKey.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract groupId", e)
            null
        }
    }
}
