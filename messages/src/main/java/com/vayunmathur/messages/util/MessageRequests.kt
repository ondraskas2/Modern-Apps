package com.vayunmathur.messages.util

import com.vayunmathur.messages.data.Conversation
import org.json.JSONObject

/**
 * Message-request flag helpers. The flag lives inside the conversation's
 * [Conversation.serviceData] JSON (key "isMessageRequest") so no Room
 * schema migration is needed. Different sources populate it differently
 * (Signal writes serviceData directly; Meta/Instagram emit a separate
 * MessageRequestReceived event) — these helpers give one read/write path.
 */
private const val MESSAGE_REQUEST_KEY = "isMessageRequest"

/** True iff this conversation is an unaccepted message request. */
fun Conversation.isMessageRequest(): Boolean =
    serviceDataFlag(serviceData, MESSAGE_REQUEST_KEY)

internal fun serviceDataFlag(serviceData: String?, key: String): Boolean {
    if (serviceData.isNullOrBlank()) return false
    return runCatching { JSONObject(serviceData).optBoolean(key, false) }
        .getOrDefault(false)
}

/** Return [serviceData] JSON with [key] set to [value], preserving other keys. */
internal fun withServiceDataFlag(serviceData: String?, key: String, value: Boolean): String {
    val obj = runCatching {
        if (serviceData.isNullOrBlank()) JSONObject() else JSONObject(serviceData)
    }.getOrDefault(JSONObject())
    obj.put(key, value)
    return obj.toString()
}

internal fun withMessageRequestFlag(serviceData: String?, value: Boolean): String =
    withServiceDataFlag(serviceData, MESSAGE_REQUEST_KEY, value)

/**
 * Overlay [incoming] serviceData JSON keys onto [existing], so independent
 * producers (e.g. a group's participantNames update vs. a separate
 * message-request flag) don't clobber each other's keys. Incoming keys win;
 * keys only in existing are preserved. Falls back to the freshest non-null
 * value if either side isn't valid JSON.
 */
internal fun mergeServiceData(existing: String?, incoming: String?): String? {
    if (existing.isNullOrBlank()) return incoming
    if (incoming.isNullOrBlank()) return existing
    return runCatching {
        val base = JSONObject(existing)
        val inc = JSONObject(incoming)
        for (key in inc.keys()) base.put(key, inc.get(key))
        base.toString()
    }.getOrDefault(incoming)
}

/**
 * Participant display names for a group conversation, read from the
 * [Conversation.serviceData] JSON array under key "participantNames".
 * Platform clients populate this on the group's ConversationUpdate.
 */
fun Conversation.participantNames(): List<String> {
    val sd = serviceData ?: return emptyList()
    return runCatching {
        val arr = JSONObject(sd).optJSONArray("participantNames") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            arr.optString(i).takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())
}
