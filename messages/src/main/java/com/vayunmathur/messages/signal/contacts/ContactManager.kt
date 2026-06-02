package com.vayunmathur.messages.signal.contacts

import android.util.Log
import com.vayunmathur.messages.data.MessageSource
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.store.SignalRecipientEntity
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import com.vayunmathur.messages.util.ContactSuggestion

class ContactManager(
    private val recipientStore: SignalRecipientStore,
) {
    suspend fun handleContactSync(contacts: List<SignalServiceProtos.ContactDetails>) {
        for (contact in contacts) {
            val entity = SignalRecipientEntity(
                aci = contact.aci,
                e164 = contact.number,
                profileName = contact.name,
            )
            recipientStore.storeRecipient(entity)
        }
        Log.d(TAG, "Synced ${contacts.size} contacts")
    }

    suspend fun getDisplayName(aci: String): String? {
        val recipient = recipientStore.getRecipient(aci) ?: return null
        return recipient.profileName ?: recipient.e164
    }

    suspend fun searchContacts(query: String): List<ContactSuggestion> {
        val lowerQuery = query.lowercase()
        val all = recipientStore.getAllRecipients()
        return all.filter { recipient ->
            recipient.profileName?.lowercase()?.contains(lowerQuery) == true ||
                recipient.e164?.contains(lowerQuery) == true
        }.map { recipient ->
            ContactSuggestion(
                displayName = recipient.profileName ?: recipient.e164 ?: recipient.aci,
                phoneE164 = recipient.e164,
                avatarUrl = recipient.profileAvatar,
                source = MessageSource.SIGNAL,
            )
        }
    }

    companion object {
        private const val TAG = "ContactManager"
    }
}
