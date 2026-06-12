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
        timestamp: Long = System.currentTimeMillis(),
    ): SignalServiceProtos.Content {
        val authorAciBytes = MessageSender.uuidToBytes(targetAuthorAci)
        val reaction = SignalServiceProtos.DataMessage.Reaction.newBuilder()
            .setEmoji(emoji)
            .setTargetAuthorAciBinary(ByteString.copyFrom(authorAciBytes))
            .setTargetSentTimestamp(targetTimestamp)
            .setRemove(remove)

        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setReaction(reaction.build())
            .setTimestamp(timestamp)
            .setRequiredProtocolVersion(SignalServiceProtos.DataMessage.ProtocolVersion.REACTIONS_VALUE)

        return SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())
            .build()
    }

    fun deleteMessage(targetTimestamp: Long, timestamp: Long = System.currentTimeMillis()): SignalServiceProtos.Content {
        val delete = SignalServiceProtos.DataMessage.Delete.newBuilder()
            .setTargetSentTimestamp(targetTimestamp)

        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setDelete(delete.build())
            .setTimestamp(timestamp)

        return SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())
            .build()
    }

    fun typingMessage(isTyping: Boolean, timestamp: Long, groupId: ByteArray? = null): SignalServiceProtos.Content {
        val action = if (isTyping) {
            SignalServiceProtos.TypingMessage.Action.STARTED
        } else {
            SignalServiceProtos.TypingMessage.Action.STOPPED
        }

        val typing = SignalServiceProtos.TypingMessage.newBuilder()
            .setTimestamp(timestamp)
            .setAction(action)

        if (groupId != null) {
            typing.setGroupId(ByteString.copyFrom(groupId))
        }

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

    fun groupCallUpdateMessage(
        eraId: String?,
        groupContext: SignalServiceProtos.GroupContextV2,
        timestamp: Long,
    ): SignalServiceProtos.Content {
        val gcuBuilder = SignalServiceProtos.DataMessage.GroupCallUpdate.newBuilder()
        if (eraId != null) {
            gcuBuilder.setEraId(eraId)
        }
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setGroupCallUpdate(gcuBuilder.build())
            .setGroupV2(groupContext)
            .setTimestamp(timestamp)

        return SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())
            .build()
    }

    fun syncReadMessage(
        senderAci: String,
        timestamps: List<Long>,
    ): SignalServiceProtos.Content {
        val syncBuilder = SignalServiceProtos.SyncMessage.newBuilder()
        timestamps.forEach { ts ->
            syncBuilder.addRead(
                SignalServiceProtos.SyncMessage.Read.newBuilder()
                    .setSenderAci(senderAci)
                    .setTimestamp(ts)
                    .build()
            )
        }
        val rng = java.security.SecureRandom()
        val syncPadding = ByteArray(rng.nextInt(511) + 1)
        rng.nextBytes(syncPadding)
        syncBuilder.setPadding(ByteString.copyFrom(syncPadding))

        return SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(syncBuilder.build())
            .build()
    }

    fun wrapSyncMessage(sync: SignalServiceProtos.SyncMessage): SignalServiceProtos.Content {
        val syncWithPadding = sync.toBuilder()
        val rng = java.security.SecureRandom()
        val padding = ByteArray(rng.nextInt(511) + 1)
        rng.nextBytes(padding)
        syncWithPadding.setPadding(ByteString.copyFrom(padding))

        return SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(syncWithPadding.build())
            .build()
    }

    fun disappearingTimerMessage(
        expirationSeconds: Int,
        timestamp: Long,
        expireTimerVersion: Int = 1,
    ): SignalServiceProtos.Content {
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setTimestamp(timestamp)
            .setFlags(SignalServiceProtos.DataMessage.Flags.EXPIRATION_TIMER_UPDATE_VALUE)
            .setExpireTimer(expirationSeconds)
            .setExpireTimerVersion(expireTimerVersion)

        return SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())
            .build()
    }

    fun pollCreateMessage(
        question: String,
        options: List<String>,
        allowMultiple: Boolean,
        timestamp: Long,
    ): SignalServiceProtos.Content {
        val pollCreate = SignalServiceProtos.DataMessage.PollCreate.newBuilder()
            .setQuestion(question)
            .setAllowMultiple(allowMultiple)
        options.forEach { pollCreate.addOptions(it) }

        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setPollCreate(pollCreate.build())
            .setTimestamp(timestamp)
            .setRequiredProtocolVersion(SignalServiceProtos.DataMessage.ProtocolVersion.POLLS_VALUE)

        return SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())
            .build()
    }

    fun pollVoteMessage(
        targetAuthorAci: ByteArray,
        targetTimestamp: Long,
        optionIndexes: List<Int>,
        timestamp: Long,
    ): SignalServiceProtos.Content {
        val pollVote = SignalServiceProtos.DataMessage.PollVote.newBuilder()
            .setTargetAuthorAciBinary(ByteString.copyFrom(targetAuthorAci))
            .setTargetSentTimestamp(targetTimestamp)
            .setVoteCount(1)
        optionIndexes.forEach { pollVote.addOptionIndexes(it) }

        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setPollVote(pollVote.build())
            .setTimestamp(timestamp)
            .setRequiredProtocolVersion(0)

        return SignalServiceProtos.Content.newBuilder()
            .setDataMessage(dm.build())
            .build()
    }

    fun deleteForMeSyncMessage(
        conversationId: SignalServiceProtos.ConversationIdentifier,
        mostRecentMessages: List<SignalServiceProtos.AddressableMessage>,
        isFullDelete: Boolean = true,
    ): SignalServiceProtos.Content {
        val deleteForMe = SignalServiceProtos.SyncMessage.DeleteForMe.newBuilder()
            .addConversationDeletes(
                SignalServiceProtos.SyncMessage.DeleteForMe.ConversationDelete.newBuilder()
                    .setConversation(conversationId)
                    .addAllMostRecentMessages(mostRecentMessages)
                    .setIsFullDelete(isFullDelete)
                    .build()
            )

        val sync = SignalServiceProtos.SyncMessage.newBuilder()
            .setDeleteForMe(deleteForMe.build())

        return wrapSyncMessage(sync.build())
    }

    fun viewedReceipt(timestamps: List<Long>): SignalServiceProtos.Content {
        return receiptMessage(SignalServiceProtos.ReceiptMessage.Type.VIEWED, timestamps)
    }

    fun messageRequestResponseSync(
        threadAci: String? = null,
        groupId: ByteArray? = null,
        type: SignalServiceProtos.SyncMessage.MessageRequestResponse.Type,
    ): SignalServiceProtos.Content {
        val mrr = SignalServiceProtos.SyncMessage.MessageRequestResponse.newBuilder()
            .setType(type)
        if (threadAci != null) {
            val uuidBytes = MessageSender.uuidToBytes(threadAci)
            mrr.setThreadAciBinary(ByteString.copyFrom(uuidBytes))
        }
        if (groupId != null) {
            mrr.setGroupId(ByteString.copyFrom(groupId))
        }

        val sync = SignalServiceProtos.SyncMessage.newBuilder()
            .setMessageRequestResponse(mrr.build())

        return wrapSyncMessage(sync.build())
    }
}
