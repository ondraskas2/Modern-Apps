package com.vayunmathur.passwords.util

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Log
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.data.PasswordDatabase
import com.vayunmathur.passwords.ui.PasskeyAuthActivity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

class PasskeyCredentialService : CredentialProviderService() {

    private val db by lazy {
        applicationContext.buildDatabase<PasswordDatabase>()
    }
    private val passkeyDao by lazy { db.passkeyDao() }
    private val passwordDao by lazy { db.passwordDao() }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        runBlocking {
            try {
                val responseBuilder = BeginGetCredentialResponse.Builder()

                for (option in request.beginGetCredentialOptions) {
                    when (option) {
                        is BeginGetPublicKeyCredentialOption -> {
                            val json = JSONObject(option.requestJson)
                            val rpId = json.optString("rpId", "")
                            if (rpId.isBlank()) continue

                            val passkeys = passkeyDao.getByRpId(rpId)
                            for (passkey in passkeys) {
                                val intent = Intent(applicationContext, PasskeyAuthActivity::class.java).apply {
                                    putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_GET)
                                    putExtra(PasskeyAuthActivity.EXTRA_CREDENTIAL_ID, passkey.credentialId)
                                }
                                val pendingIntent = PendingIntent.getActivity(
                                    applicationContext,
                                    passkey.id.toInt(),
                                    intent,
                                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                                )
                                val entry = PublicKeyCredentialEntry.Builder(
                                    applicationContext,
                                    passkey.userName,
                                    pendingIntent,
                                    option,
                                ).build()
                                responseBuilder.addCredentialEntry(entry)
                            }
                        }
                        is BeginGetPasswordOption -> {
                            val allPasswords = passwordDao.getAll()
                            for (pass in allPasswords) {
                                val intent = Intent(applicationContext, PasskeyAuthActivity::class.java).apply {
                                    putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_PASSWORD)
                                    putExtra(EXTRA_PASSWORD_ID, pass.id)
                                }
                                val pendingIntent = PendingIntent.getActivity(
                                    applicationContext,
                                    (pass.id + 100000).toInt(),
                                    intent,
                                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                                )
                                val entry = PasswordCredentialEntry.Builder(
                                    applicationContext,
                                    pass.userId,
                                    pendingIntent,
                                    option,
                                ).setDisplayName(pass.name.ifBlank { null })
                                    .build()
                                responseBuilder.addCredentialEntry(entry)
                            }
                        }
                    }
                }
                callback.onResult(responseBuilder.build())
            } catch (e: Exception) {
                Log.e(TAG, "onBeginGetCredentialRequest failed", e)
                callback.onError(GetCredentialUnknownException())
            }
        }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        try {
            if (request is BeginCreatePublicKeyCredentialRequest) {
                val intent = Intent(applicationContext, PasskeyAuthActivity::class.java).apply {
                    putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_CREATE)
                }
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val createEntry = CreateEntry.Builder(
                    applicationContext.getString(R.string.app_name),
                    pendingIntent,
                ).build()

                callback.onResult(
                    BeginCreateCredentialResponse.Builder()
                        .addCreateEntry(createEntry)
                        .build()
                )
            } else {
                callback.onResult(BeginCreateCredentialResponse.Builder().build())
            }
        } catch (e: Exception) {
            Log.e(TAG, "onBeginCreateCredentialRequest failed", e)
            callback.onError(CreateCredentialUnknownException())
        }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }

    companion object {
        private const val TAG = "PasskeyCredService"
        const val EXTRA_PASSWORD_ID = "password_id"
    }
}
