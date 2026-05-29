package com.vayunmathur.email.data

import android.content.Context
import android.util.Log
import androidx.work.*
import com.vayunmathur.email.EmailManager
import com.vayunmathur.email.widget.EmailWidget
import androidx.glance.appwidget.updateAll
import java.util.concurrent.TimeUnit

class EmailSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val db = EmailDatabase.getInstance(applicationContext)
        val dao = db.emailDao()
        // Mutable so we can swap in a refreshed account on auth failure.
        val accounts = dao.getAccounts().toMutableList()

        if (accounts.isEmpty()) {
            Log.d("EmailSync", "No accounts to sync")
            return Result.success()
        }

        EmailSyncState.start()
        val manager = EmailManager()
        var hasErrors = false
        var accountsProcessed = 0

        for ((i, original) in accounts.withIndex()) {
            // `account` may be replaced with a refreshed copy below.
            var account = original
            try {
                Log.d("EmailSync", ">>> Starting sync for account: ${account.email}")
                var auth = EmailManager.AuthType.OAuth2(account.accessToken)

                // Open a SINGLE store for the whole account — folders + every
                // folder's message sync all reuse one TCP/TLS connection.
                // On auth failure we refresh the token and retry once.
                suspend fun runSync(authToUse: EmailManager.AuthType, accountToUse: com.vayunmathur.email.EmailAccount) {
                    manager.withStore("imap.gmail.com", accountToUse.email, authToUse) { store ->
                        Log.d("EmailSync", "Fetching folders for ${accountToUse.email}...")
                        val folders = manager.fetchFoldersInStore(store, accountToUse.email)
                        dao.insertFolders(folders)
                        Log.d("EmailSync", "Synced ${folders.size} folders.")

                        // Folders we actually fetch messages from:
                        //   - Must hold messages
                        //   - Skip Gmail's virtual folders that mirror INBOX (saves a huge
                        //     amount of duplicate downloads).
                        val messageFolders = folders.filter { folder ->
                            folder.holdsMessages && folder.fullName !in GMAIL_VIRTUAL_FOLDERS
                        }
                        val totalUnits = (accounts.size * messageFolders.size).coerceAtLeast(1)

                        for ((index, folder) in messageFolders.withIndex()) {
                            try {
                                val knownUids = dao.getKnownUids(accountToUse.email, folder.fullName).toSet()
                                val (messages, attachments) = manager.fetchMessagesInStore(
                                    store = store,
                                    user = accountToUse.email,
                                    folderName = folder.fullName,
                                    limit = 50,
                                    // Don't fetch full message bodies here — that's the
                                    // biggest chunk of network. The MessageThread screen
                                    // lazy-loads each body on first open.
                                    fetchBodies = false,
                                    skipUids = knownUids,
                                )
                                if (messages.isNotEmpty()) dao.insertMessages(messages)
                                if (attachments.isNotEmpty()) dao.insertAttachments(attachments)

                                // Notifications: only for INBOX, only when the app
                                // isn't already in the foreground, and only for UIDs
                                // strictly greater than the last UID we've already
                                // surfaced — so the first sync of an account doesn't
                                // dump 50 notifications at once.
                                if (folder.fullName == "INBOX" && messages.isNotEmpty()) {
                                    val lastSeen = lastSeenPrefs(applicationContext)
                                        .getLong(lastSeenKey(accountToUse.email, folder.fullName), -1L)
                                    if (lastSeen < 0L) {
                                        // First time: baseline only, no notifications.
                                    } else if (!com.vayunmathur.email.util.AppLifecycleTracker.isAppInForeground) {
                                        val notifiable = messages.filter { it.id > lastSeen }
                                        com.vayunmathur.email.util.EmailNotifications.postForNewMessages(
                                            applicationContext, accountToUse.email, notifiable,
                                        )
                                    }
                                    val maxUid = messages.maxOf { it.id }
                                    if (maxUid > lastSeen) {
                                        lastSeenPrefs(applicationContext).edit()
                                            .putLong(lastSeenKey(accountToUse.email, folder.fullName), maxUid)
                                            .apply()
                                    }
                                }

                                Log.d("EmailSync", "[${index + 1}/${messageFolders.size}] ${folder.fullName}: ${messages.size} new (skipped ${knownUids.size}).")
                            } catch (e: Exception) {
                                Log.e("EmailSync", "   x Failed folder ${folder.fullName}", e)
                            }
                            val unitsDone = accountsProcessed * messageFolders.size + (index + 1)
                            EmailSyncState.setProgress(unitsDone.toFloat() / totalUnits)
                        }

                        // ------- Background body backfill -------
                        // The user's interactive `fetchBodyIfNeeded` runs in a
                        // separate connection so we don't have to coordinate; here we
                        // just stream missing bodies in over the same store we used
                        // above. Bail as soon as `isStopped` flips (e.g. user pulls
                        // to refresh, scheduling a new sync).
                        val missing = dao.getMessagesWithoutBody(accountToUse.email, BACKFILL_LIMIT)
                        if (missing.isNotEmpty()) {
                            Log.d("EmailSync", "Body backfill: ${missing.size} message(s)")
                            EmailSyncState.setProgress(0f)
                            for ((idx, msg) in missing.withIndex()) {
                                if (isStopped) {
                                    Log.d("EmailSync", "Backfill stopped at ${idx}/${missing.size}")
                                    break
                                }
                                try {
                                    // Re-check: UI may have just filled this one in.
                                    val current = dao.getMessage(msg.accountEmail, msg.folderName, msg.id) ?: continue
                                    if (current.body != null) continue
                                    val (body, isHtml, attachments) = manager.fetchMessageBodyInStore(
                                        store = store,
                                        user = accountToUse.email,
                                        folderName = msg.folderName,
                                        uid = msg.id,
                                    )
                                    if (body != null || attachments.isNotEmpty()) {
                                        dao.insertMessages(listOf(current.copy(
                                            body = body,
                                            isHtml = isHtml,
                                            hasAttachments = attachments.isNotEmpty(),
                                        )))
                                        if (attachments.isNotEmpty()) dao.insertAttachments(attachments)
                                    }
                                } catch (e: Exception) {
                                    Log.w("EmailSync", "   x Backfill failed for UID ${msg.id}: ${e.message}")
                                }
                                EmailSyncState.setProgress((idx + 1f) / missing.size)
                            }
                            Log.d("EmailSync", "Backfill done for ${accountToUse.email}")
                        }
                    }
                }

                try {
                    runSync(auth, account)
                } catch (e: javax.mail.AuthenticationFailedException) {
                    Log.d("EmailSync", "Auth failed for ${account.email}; refreshing token")
                    val refreshed = TokenRefresher.refresh(applicationContext, account)
                        ?: throw e
                    account = refreshed
                    accounts[i] = refreshed
                    auth = EmailManager.AuthType.OAuth2(refreshed.accessToken)
                    runSync(auth, refreshed)
                }

                Log.d("EmailSync", "<<< Completed sync for account: ${account.email}")
            } catch (e: Exception) {
                Log.e("EmailSync", "Failed to sync account ${account.email}", e)
                hasErrors = true
            }
            accountsProcessed++
        }

        EmailWidget().updateAll(applicationContext)
        EmailSyncState.finish()

        return if (hasErrors) Result.retry() else Result.success()
    }

    companion object {
        private const val SYNC_WORK_NAME = "EmailSyncWorker"

        /**
         * Gmail's IMAP exposes several "virtual" labels that mirror INBOX (and
         * other folders) — syncing them downloads everything twice. Skipping
         * them is a major sync-time win.
         */
        private val GMAIL_VIRTUAL_FOLDERS = setOf(
            "[Gmail]/All Mail",
            "[Gmail]/Important",
            "[Gmail]/Starred",
            "[Gmail]/Chats",
        )

        /** How many missing-body messages to backfill per worker run. */
        private const val BACKFILL_LIMIT = 200

        // ---- Notification baseline ----

        private fun lastSeenPrefs(context: Context) =
            context.getSharedPreferences("email_notif_last_seen", Context.MODE_PRIVATE)

        private fun lastSeenKey(accountEmail: String, folderName: String) =
            "$accountEmail::$folderName"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<EmailSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }

        fun runOneOffSync(context: Context) {
            val syncRequest = OneTimeWorkRequestBuilder<EmailSyncWorker>()
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
        }
        
        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        }
    }
}
