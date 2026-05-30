package com.vayunmathur.messages.util

import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Parses an inbound [Intent] into a [IncomingIntent] the UI can act on.
 *
 * Three intent shapes are recognized:
 *  - **Notification deep-link** — an explicit launch with
 *    [MessagesService.EXTRA_OPEN_CONVERSATION] set. Routes the user
 *    straight to the right conversation.
 *  - **sms / mms SENDTO/VIEW** — e.g. `smsto:+15551234567?body=hi%20there`.
 *    Routes to the Compose screen with the number + body pre-filled.
 *  - **ACTION_SEND / ACTION_SEND_MULTIPLE** — share-sheet target.
 *    Routes to the Compose screen with text and any attached media URIs
 *    pre-loaded.
 *
 * Returns null when the intent is the bare launcher MAIN intent (no
 * navigation override needed; the activity opens at its default route).
 */
sealed interface IncomingIntent {
    data class OpenConversation(val conversationId: String) : IncomingIntent
    data class OpenNumber(val phone: String, val prefilledBody: String?) : IncomingIntent
    data class Share(
        val text: String?,
        val mediaUris: List<Uri>,
        /** Best-effort mime hint from the originating app (e.g. "image/png" or `\u002a/\u002a`). */
        val mime: String?,
    ) : IncomingIntent
}

object ShareIntentParser {

    fun parse(intent: Intent?): IncomingIntent? {
        if (intent == null) return null

        // 1. Notification deep-link — explicit extra.
        intent.getStringExtra(MessagesService.EXTRA_OPEN_CONVERSATION)
            ?.takeIf { it.isNotBlank() }
            ?.let { return IncomingIntent.OpenConversation(it) }

        return when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_SENDTO -> parseSmsIntent(intent)
            Intent.ACTION_SEND -> parseShareIntent(intent, multiple = false)
            Intent.ACTION_SEND_MULTIPLE -> parseShareIntent(intent, multiple = true)
            else -> null
        }
    }

    /** Decode an `sms:` / `smsto:` / `mms:` / `mmsto:` URI. */
    private fun parseSmsIntent(intent: Intent): IncomingIntent? {
        val uri = intent.data ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in SMS_SCHEMES) return null
        // sms:+15551234567 / smsto:+15551234567 — the number lives in
        // the scheme-specific-part, NOT path/host. URLDecode it to
        // collapse %2B → + etc.
        val raw = uri.schemeSpecificPart.orEmpty().substringBefore('?')
        val phone = Uri.decode(raw).trim().takeIf { it.isNotBlank() }
            ?: return null
        // `body` (Android-canonical) and `sms_body` (some senders) both
        // carry the prefilled text. Either is accepted.
        val body = uri.getQueryParameter("body")
            ?: uri.getQueryParameter("sms_body")
        return IncomingIntent.OpenNumber(phone, body)
    }

    /** Decode ACTION_SEND / ACTION_SEND_MULTIPLE share-sheet intents. */
    @Suppress("DEPRECATION")
    private fun parseShareIntent(intent: Intent, multiple: Boolean): IncomingIntent? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val mime = intent.type
        val mediaUris: List<Uri> = if (multiple) {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            list?.toList().orEmpty()
        } else {
            val single = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            listOfNotNull(single)
        }
        if (text.isNullOrBlank() && mediaUris.isEmpty()) return null
        return IncomingIntent.Share(
            text = text?.takeIf { it.isNotBlank() },
            mediaUris = mediaUris,
            mime = mime,
        )
    }

    private val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")
}
