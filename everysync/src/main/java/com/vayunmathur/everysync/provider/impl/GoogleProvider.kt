package com.vayunmathur.everysync.provider.impl

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import com.vayunmathur.everysync.auth.AccountConfig
import com.vayunmathur.everysync.auth.OAuthConfig
import com.vayunmathur.everysync.auth.OAuthManager
import com.vayunmathur.everysync.auth.OAuthTokens
import com.vayunmathur.everysync.provider.AuthType
import com.vayunmathur.everysync.provider.DataType
import com.vayunmathur.everysync.provider.SyncDirection
import com.vayunmathur.everysync.provider.SyncProvider
import com.vayunmathur.everysync.provider.SyncState
import com.vayunmathur.everysync.remote.GoogleCalendarClient
import com.vayunmathur.everysync.remote.GooglePeopleClient
import com.vayunmathur.everysync.sink.CalendarSink
import com.vayunmathur.everysync.sink.ContactsSink
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.ui.IconProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Google (People API + Calendar API), OAuth PKCE, contacts + calendar, two-way. */
class GoogleProvider : SyncProvider {
    override val id = "google"
    override val displayName = "Google"
    override val icon: @Composable () -> Unit = { IconProvider() }
    override val authType = AuthType.OAUTH
    override val capabilities = setOf(DataType.CONTACTS, DataType.CALENDAR)

    override fun oauthConfig(): OAuthConfig = OAuthConfig.GOOGLE

    override suspend fun resolveAccountName(context: Context, tokens: OAuthTokens): String {
        return try {
            val resp = NetworkClient.performRequest(
                "https://www.googleapis.com/oauth2/v3/userinfo", "GET",
                mapOf("Authorization" to "Bearer ${tokens.accessToken}"),
            )
            val email = (JSON.parseToJsonElement(resp.body) as? JsonObject)
                ?.get("email")?.jsonPrimitive?.content
            if (!email.isNullOrBlank()) "$email (Google)" else "Google account"
        } catch (e: Exception) {
            Log.e(TAG, "resolveAccountName failed", e)
            "Google account"
        }
    }

    override suspend fun sync(context: Context, config: AccountConfig, direction: SyncDirection) {
        val token = OAuthManager.validAccessToken(context, config.accountName, id) ?: return
        val account = config.accountName

        if (DataType.CONTACTS in config.enabledTypes) syncContacts(context, account, token, direction)
        if (DataType.CALENDAR in config.enabledTypes) syncCalendars(context, account, token, direction)
    }

    private suspend fun syncContacts(context: Context, account: String, token: String, direction: SyncDirection) {
        val people = GooglePeopleClient(token)
        if (direction != SyncDirection.PUSH) {
            val result = people.listConnections(SyncState.get(context, account, "google_contacts"))
            for (c in result.contacts) {
                if (c.deleted) ContactsSink.delete(context, account, c.uid)
                else ContactsSink.upsert(context, account, c)
            }
            result.nextSyncToken?.let { SyncState.set(context, account, "google_contacts", it) }
        }
        if (direction != SyncDirection.PULL) {
            for (change in ContactsSink.getLocalChanges(context, account)) {
                when {
                    change.deleted && change.sourceId != null -> people.deleteContact(change.sourceId)
                    !change.deleted && change.sourceId == null && change.contact != null -> {
                        val newUid = people.createContact(change.contact)
                        if (newUid != null) ContactsSink.setSourceId(context, account, change.rawContactId, newUid, null)
                    }
                }
                if (!change.deleted) ContactsSink.clearDirty(context, account, change.rawContactId)
            }
        }
    }

    private suspend fun syncCalendars(context: Context, account: String, token: String, direction: SyncDirection) {
        val cal = GoogleCalendarClient(token)
        val remoteCalendars = cal.listCalendars()
        for (rc in remoteCalendars) {
            val localCalId = CalendarSink.getOrCreateCalendarId(context, account, rc.id, rc.displayName, rc.color)
            if (localCalId == -1L) continue
            if (direction != SyncDirection.PUSH) {
                val result = cal.listEvents(rc.id, SyncState.get(context, account, "google_cal_${rc.id}"))
                for (e in result.events) {
                    if (e.deleted) CalendarSink.deleteEvent(context, account, localCalId, e.uid)
                    else CalendarSink.upsertEvent(context, account, localCalId, e)
                }
                result.nextSyncToken?.let { SyncState.set(context, account, "google_cal_${rc.id}", it) }
            }
            if (direction != SyncDirection.PULL) {
                for (change in CalendarSink.getLocalChanges(context, account, localCalId)) {
                    when {
                        change.deleted && change.syncId != null -> cal.deleteEvent(rc.id, change.syncId)
                        !change.deleted && change.syncId == null && change.event != null ->
                            cal.createEvent(rc.id, change.event)
                        !change.deleted -> CalendarSink.clearDirty(context, account, change.eventId)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "GoogleProvider"
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
