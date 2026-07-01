package com.vayunmathur.messages.gmessages

import com.vayunmathur.messages.data.MessageAttachment
import com.vayunmathur.messages.data.MessageSource

/**
 * Events emitted by [GMessagesClient] for the rest of the app to react to.
 * Shape preserved from the previous WebView-puppet design so the
 * repository / Room-write path is unchanged.
 *
 * The [conversationId] / [messageId] strings are the WEB-APP-INTERNAL ids
 * — Google's per-thread numeric ids. The data layer prefixes them with
 * the source name before persisting; see [MessageSource.idPrefix].
 */
sealed interface GMEvent {
    val source: MessageSource

    /** A conversation row's metadata changed. Fired on initial scan and
     *  on any subsequent change the long-poll surfaces. */
    data class ConversationUpdate(
        override val source: MessageSource,
        val conversationId: String,
        val peerName: String?,
        val peerPhone: String?,
        val avatarUrl: String?,
        val lastPreview: String?,
        val lastTimestamp: Long,
        val unreadCount: Int,
        val isGroup: Boolean = false,
        val participantCount: Int = 0,
        val conversationType: String? = null,
        /** Source-specific "my participant" id. See [Conversation.outgoingId]. */
        val outgoingId: String? = null,
        /** Service-specific JSON metadata to persist on the conversation row. */
        val serviceData: String? = null,
        /** True when this conversation is an unaccepted message request.
         *  Persisted into [Conversation.serviceData] JSON by the session
         *  manager (no schema bump). Sources may instead signal this via
         *  [serviceData] directly (e.g. Signal) or a separate
         *  [MessageRequestReceived] event (e.g. Meta/Instagram). */
        val isMessageRequest: Boolean = false,
    ) : GMEvent

    /** A message row appeared / was updated. Used for backfill + live sync. */
    data class MessageUpdate(
        override val source: MessageSource,
        val conversationId: String,
        val messageId: String,
        val body: String,
        val outgoing: Boolean,
        val timestamp: Long,
        val senderName: String?,
        /** Stable per-sender id (platform-specific: ACI, user id, JID, …).
         *  Used to coalesce consecutive same-sender bubbles in group chats
         *  even when two participants share a display name. */
        val senderId: String? = null,
        val reactionsJson: String? = null,
        /** Downloaded media bytes (null if no media or download failed). */
        val mediaData: ByteArray? = null,
        val mediaMime: String? = null,
        val mediaName: String? = null,
        /** Wire status type string for status tracking. */
        val statusType: String? = null,
        /** Service-specific JSON metadata to persist on the message row. */
        val serviceData: String? = null,
        /** Inline media attachments to render (URL-based). Empty = none. */
        val attachments: List<MessageAttachment> = emptyList(),
    ) : GMEvent

    /** A NEW inbound message just arrived. Distinct from MessageUpdate
     *  because it should fire a notification; MessageUpdate is just sync. */
    data class IncomingMessage(
        override val source: MessageSource,
        val conversationId: String,
        val messageId: String,
        val body: String,
        val peerName: String?,
        val peerPhone: String?,
        val timestamp: Long,
        /** Display name of the actual message sender. In group chats this
         *  is the individual participant (NOT [peerName], which identifies
         *  the conversation). Null in 1:1 chats → falls back to [peerName]. */
        val senderName: String? = null,
        /** Stable per-sender id for the actual sender (see MessageUpdate.senderId). */
        val senderId: String? = null,
        /** Inline media attachments to render (URL-based). Empty = none. */
        val attachments: List<MessageAttachment> = emptyList(),
    ) : GMEvent

    /** A message was deleted on the remote side (or by us). */
    data class MessageDeleted(
        override val source: MessageSource,
        val messageId: String,
        val conversationId: String? = null,
        val timestamp: Long = 0L,
    ) : GMEvent

    /** A message was edited on the remote side. */
    data class MessageEdited(
        override val source: MessageSource,
        val conversationId: String? = null,
        val messageId: String,
        val newBody: String,
        val timestamp: Long = 0L,
    ) : GMEvent

    /** A read receipt was received. */
    data class ReadReceipt(
        override val source: MessageSource,
        val conversationId: String,
        val messageId: String? = null,
        val senderId: String? = null,
        val timestampMs: Long = 0L,
        val timestamp: Long = 0L,
        val isDelivery: Boolean = false,
    ) : GMEvent

    /** A conversation was deleted on the remote side. */
    data class ConversationDeleted(
        override val source: MessageSource,
        val conversationId: String,
    ) : GMEvent

    /** A remote user started or stopped typing. */
    data class TypingIndicator(
        override val source: MessageSource,
        val conversationId: String,
        val senderId: String,
        val isTyping: Boolean,
    ) : GMEvent

    /** A reaction was added to a message. */
    data class ReactionReceived(
        override val source: MessageSource,
        val conversationId: String,
        val messageId: String,
        val senderId: String,
        val emoji: String,
    ) : GMEvent

    /** A reaction was removed from a message. */
    data class ReactionRemoved(
        override val source: MessageSource,
        val conversationId: String,
        val messageId: String,
        val senderId: String,
    ) : GMEvent

    /** A conversation was renamed. */
    data class ConversationNameChanged(
        override val source: MessageSource,
        val conversationId: String,
        val newName: String,
    ) : GMEvent

    /** A conversation avatar was changed. */
    data class ConversationAvatarChanged(
        override val source: MessageSource,
        val conversationId: String,
        val avatarUrl: String?,
    ) : GMEvent

    /** A participant was added to a group. */
    data class ParticipantAdded(
        override val source: MessageSource,
        val conversationId: String,
        val participantId: String,
    ) : GMEvent

    /** A participant was removed from a group. */
    data class ParticipantRemoved(
        override val source: MessageSource,
        val conversationId: String,
        val participantId: String,
    ) : GMEvent

    /** A conversation's mute setting changed. */
    data class MuteSettingChanged(
        override val source: MessageSource,
        val conversationId: String,
        val muteExpireTimeMs: Long,
    ) : GMEvent

    /** A message request was received for a conversation. */
    data class MessageRequestReceived(
        override val source: MessageSource,
        val conversationId: String,
    ) : GMEvent

    /** A message send failed with a detailed error. */
    data class SendFailed(
        override val source: MessageSource,
        val conversationId: String,
        val messageId: String? = null,
        val tmpId: String? = null,
        val errorMessage: String,
    ) : GMEvent

    /** A decryption error occurred (matching Go: DecryptionError event). */
    data class DecryptionError(
        override val source: MessageSource,
        val conversationId: String,
        val senderAci: String,
        val senderDeviceId: Int,
        val timestamp: Long,
        val errorMessage: String? = null,
    ) : GMEvent

    /** A source was logged out / unlinked. Consumers should drop that source's cached
     *  conversations + messages so stale threads don't linger. */
    data class SourceLoggedOut(
        override val source: MessageSource,
    ) : GMEvent
}
