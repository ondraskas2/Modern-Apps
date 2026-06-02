package com.vayunmathur.messages.signal.sending

import com.google.protobuf.ByteString
import com.vayunmathur.messages.signal.proto.SignalServiceProtos

object ContentBuilders {

    fun textMessage(
        body: String,
        timestamp: Long,
        profileKey: ByteArray? = null,
    ): SignalServiceProtos.Content {
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setBody(body)
            .setTimestamp(timestamp)
        if (profileKey != null) {
            dm.setProfileKey(ByteString.copyFrom(profileKey))
        }
        return SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())
            .build()
    }

    fun reactionMessage(
        emoji: String,
        targetAuthorAci: String,
        targetTimestamp: Long,
        remove: Boolean = false,
    ): SignalServiceProtos.Content {
        val reaction = SignalServiceProtos.DataMessage.Reaction.newBuilder()
            .setEmoji(emoji)
            .setTargetAuthorAci(targetAuthorAci)
            .setTargetSentTimestamp(targetTimestamp)
            .setRemove(remove)

        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setReaction(reaction.build())
            .setTimestamp(System.currentTimeMillis())

        return SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())
            .build()
    }

    fun deleteMessage(targetTimestamp: Long): SignalServiceProtos.Content {
        val delete = SignalServiceProtos.DataMessage.Delete.newBuilder()
            .setTargetSentTimestamp(targetTimestamp)

        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setDelete(delete.build())
            .setTimestamp(System.currentTimeMillis())

        return SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())
            .build()
    }

    fun typingMessage(isTyping: Boolean, timestamp: Long): SignalServiceProtos.Content {
        val action = if (isTyping) {
            SignalServiceProtos.TypingMessage.Action.STARTED
        } else {
            SignalServiceProtos.TypingMessage.Action.STOPPED
        }

        val typing = SignalServiceProtos.TypingMessage.newBuilder()
            .setTimestamp(timestamp)
            .setAction(action)

        return SignalServiceProtos.Content.newBuilder()
            .setTypingMessage(typing.build())
            .build()
    }

    fun readReceipt(timestamps: List<Long>): SignalServiceProtos.Content {
        return receiptMessage(SignalServiceProtos.ReceiptMessage.Type.READ, timestamps)
    }

    fun deliveryReceipt(timestamps: List<Long>): SignalServiceProtos.Content {
        return receiptMessage(SignalServiceProtos.ReceiptMessage.Type.DELIVERY, timestamps)
    }

    fun editMessage(
        targetTimestamp: Long,
        newBody: String,
        editTimestamp: Long,
    ): SignalServiceProtos.Content {
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setBody(newBody)
            .setTimestamp(editTimestamp)

        val edit = SignalServiceProtos.EditMessage.newBuilder()
            .setTargetSentTimestamp(targetTimestamp)
            .setDataMessage(dm.build())

        return SignalServiceProtos.Content.newBuilder()
            .setEditMessage(edit.build())
            .build()
    }

    private fun receiptMessage(
        type: SignalServiceProtos.ReceiptMessage.Type,
        timestamps: List<Long>,
    ): SignalServiceProtos.Content {
        val receipt = SignalServiceProtos.ReceiptMessage.newBuilder()
            .setType(type)
        timestamps.forEach { receipt.addTimestamp(it) }

        return SignalServiceProtos.Content.newBuilder()
            .setReceiptMessage(receipt.build())
            .build()
    }
}
