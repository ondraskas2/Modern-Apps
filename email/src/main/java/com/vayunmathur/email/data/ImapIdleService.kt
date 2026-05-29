package com.vayunmathur.email.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sun.mail.imap.IMAPFolder
import com.vayunmathur.email.EmailManager
import com.vayunmathur.email.R
import javax.mail.Folder
import javax.mail.event.MessageCountAdapter
import javax.mail.event.MessageCountEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that keeps one IMAP IDLE connection open per account.
 * When Gmail pushes an `EXISTS` (new message) we kick off a one-off
 * [EmailSyncWorker] run; that worker pulls the headers, updates the DB, and
 * (via the notification code) raises a system notification if the app isn't
 * in the foreground.
 *
 * IDLE connections naturally time out after ~29 minutes on most servers — we
 * just catch the disconnect and reconnect with exponential backoff. If we get
 * an `AuthenticationFailedException` we refresh the OAuth token first.
 */
class ImapIdleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountJobs = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildOngoingNotification(), foregroundServiceType())
        scope.launch { startIdleLoops() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startIdleLoops() {
        val dao = EmailDatabase.getInstance(applicationContext).emailDao()
        val accounts = dao.getAccounts()
        if (accounts.isEmpty()) {
            stopSelf()
            return
        }
        for (account in accounts) {
            accountJobs[account.email]?.cancel()
            accountJobs[account.email] = scope.launch { idleLoop(account) }
        }
    }

    private suspend fun idleLoop(initial: com.vayunmathur.email.EmailAccount) {
        var account = initial
        var backoffMs = 2_000L
        val maxBackoffMs = 60_000L
        while (scope.coroutineContext.isActive) {
            try {
                runIdleSession(account)
                // Clean return (server dropped IDLE) — short reconnect.
                backoffMs = 2_000L
                delay(1_000L)
            } catch (e: javax.mail.AuthenticationFailedException) {
                Log.d(TAG, "IDLE auth failed for ${account.email}; refreshing token")
                val refreshed = TokenRefresher.refresh(applicationContext, account)
                if (refreshed != null) {
                    account = refreshed
                    backoffMs = 2_000L
                } else {
                    Log.w(TAG, "IDLE token refresh failed for ${account.email}; sleeping")
                    delay(maxBackoffMs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "IDLE error for ${account.email}: ${e.javaClass.simpleName}: ${e.message}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
            }
        }
    }

    /**
     * Open a store, open INBOX, register a listener, then loop on `idle()`.
     * Returns when the connection is broken (idle throws) — caller handles reconnect.
     */
    private suspend fun runIdleSession(account: com.vayunmathur.email.EmailAccount) {
        withContext(Dispatchers.IO) {
            val mgr = EmailManager()
            mgr.withStore(
                host = "imap.gmail.com",
                user = account.email,
                auth = EmailManager.AuthType.OAuth2(account.accessToken),
            ) { store ->
                val folder = store.getFolder("INBOX") as IMAPFolder
                folder.open(Folder.READ_ONLY)
                folder.addMessageCountListener(object : MessageCountAdapter() {
                    override fun messagesAdded(e: MessageCountEvent) {
                        Log.d(TAG, "EXISTS for ${account.email}: ${e.messages.size} new message(s) — kicking sync")
                        EmailSyncWorker.runOneOffSync(applicationContext)
                    }
                })
                try {
                    Log.d(TAG, "Entering IDLE for ${account.email}")
                    while (scope.coroutineContext.isActive) {
                        folder.idle()
                        // idle() returns when the server pushes something or drops
                        // the connection. The MessageCountAdapter above has already
                        // run by the time we get here, so we just re-enter idle.
                    }
                } finally {
                    try { folder.close(false) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun buildOngoingNotification(): Notification {
        ensureChannel(applicationContext)
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Email")
            .setContentText("Listening for new mail")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
    }

    companion object {
        private const val TAG = "ImapIdle"
        private const val CHANNEL_ID = "imap_idle"
        private const val NOTIFICATION_ID = 9001

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Background email sync", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps an IMAP connection open so new mail arrives instantly."
                    setShowBadge(false)
                }
            )
        }

        fun start(context: Context) {
            val intent = Intent(context, ImapIdleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ImapIdleService::class.java))
        }
    }
}
