package com.vayunmathur.messages.util

import com.vayunmathur.messages.data.MessageSource

/**
 * One row in the new-conversation recipient picker.
 *
 * Backed by any of three sources of truth:
 *  - **Device contacts** via [ContactResolver] (no [MessageSource] —
 *    the user picks which backend to send through).
 *  - **gmessages** `ListTopContacts` / `ListContacts` (source =
 *    [MessageSource.MESSAGES_WEB]).
 *  - **gvoice** `AutocompleteContacts` (source = [MessageSource.VOICE]).
 *
 * The picker deduplicates by [phoneE164] across all three sources.
 * When the same phone exists in multiple sources, the device-contact
 * entry wins for `displayName`/`avatarUrl` (it's the user's own naming),
 * while the protocol-side hits contribute their [source] for the
 * "this person has a thread on…" hint in the UI.
 */
data class ContactSuggestion(
    val displayName: String,
    val phoneE164: String?,
    val avatarUrl: String?,
    /** Which backend surfaced this contact. `null` = device contact only. */
    val source: MessageSource? = null,
)
