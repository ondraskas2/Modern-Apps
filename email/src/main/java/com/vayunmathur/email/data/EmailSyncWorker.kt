package com.vayunmathur.email.data

import android.content.Context
import androidx.work.*
import com.vayunmathur.email.EmailManager
import com.vayunmathur.library.util.DataStoreUtils
import java.util.concurrent.TimeUnit

class EmailSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val dataStore = DataStoreUtils.getInstance(applicationContext)
        val accessToken = dataStore.getString("access_token")
        val userEmail = dataStore.getString("user_email")

        if (accessToken.isNullOrEmpty() || userEmail.isNullOrEmpty()) {
            return Result.failure()
        }

        val db = EmailDatabase.getInstance(applicationContext)
        val dao = db.emailDao()
        val manager = EmailManager()
        val auth = EmailManager.AuthType.OAuth2(accessToken)

        return try {
            // 1. Sync Folders
            val folders = manager.fetchFolders("imap.gmail.com", userEmail, auth)
            dao.insertFolders(folders)

            // 2. Sync Messages for each folder (limiting to top 50 for sync, bodies for top 20)
            for (folder in folders) {
                try {
                    val messages = manager.fetchMessages(
                        host = "imap.gmail.com",
                        user = userEmail,
                        auth = auth,
                        folderName = folder.fullName,
                        limit = 50,
                        offset = 0,
                        fetchBodies = true // Let's try fetching bodies for all 50 for now, or maybe less
                    )
                    dao.insertMessages(messages)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }

    companion object {
        private const val SYNC_WORK_NAME = "EmailSyncWorker"

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
