package com.vayunmathur.passwords.util

import android.content.Context
import android.util.Base64
import com.vayunmathur.library.util.BackupFormat
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordDao
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.dom.DomDatabaseWrapper
import java.io.InputStream
import java.io.OutputStream

class KdbxBackupFormat(
    private val passwordDao: PasswordDao,
    private val passkeyDao: PasskeyDao,
) : BackupFormat {
    override val mimeType = "application/octet-stream"
    override val defaultFileName = "passwords.kdbx"
    override val needsPassword = true

    override suspend fun export(context: Context, password: String?, outputStream: OutputStream) {
        requireNotNull(password) { "Password required for KDBX export" }
        val creds = KdbxCreds(password.toByteArray())
        val db = DomDatabaseWrapper()

        val root = db.rootGroup

        for (pw in passwordDao.getAll()) {
            val entry = db.newEntry()
            entry.title = pw.name
            entry.username = pw.userId
            entry.password = pw.password
            if (pw.websites.isNotEmpty()) {
                entry.url = pw.websites.first()
                if (pw.websites.size > 1) {
                    entry.setProperty("Websites", pw.websites.joinToString("\n"))
                }
            }
            pw.totpSecret?.let { secret ->
                entry.setProperty("otp", "otpauth://totp/?secret=$secret")
            }
            entry.setProperty("_Type", "password")
            root.addEntry(entry)
        }

        for (pk in passkeyDao.getAll()) {
            val entry = db.newEntry()
            entry.title = pk.rpName
            entry.username = pk.userName
            entry.url = pk.rpId
            entry.setProperty("_Type", "passkey")
            entry.setProperty("KPEX_PASSKEY_USERNAME", pk.userName)
            entry.setProperty("KPEX_PASSKEY_PRIVATE_KEY_PEM", Base64.encodeToString(pk.privateKeyBytes, Base64.NO_WRAP))
            entry.setProperty("KPEX_PASSKEY_CREDENTIAL_ID", pk.credentialId)
            entry.setProperty("KPEX_PASSKEY_USER_HANDLE", pk.userId)
            entry.setProperty("KPEX_PASSKEY_RELYING_PARTY", pk.rpId)
            root.addEntry(entry)
        }

        db.save(creds, outputStream)
    }

    override suspend fun import(context: Context, password: String?, inputStream: InputStream) {
        requireNotNull(password) { "Password required for KDBX import" }
        val creds = KdbxCreds(password.toByteArray())
        val db = DomDatabaseWrapper.load(creds, inputStream)

        suspend fun processGroup(group: org.linguafranca.pwdb.Group<*, *, *, *>) {
            for (i in 0 until group.entriesCount) {
                @Suppress("UNCHECKED_CAST")
                val entry = group.entries[i] as org.linguafranca.pwdb.Entry<*, *, *, *>
                val isPasskey = entry.propertyNames.any { it.startsWith("KPEX_PASSKEY_") }
                if (isPasskey) {
                    importPasskey(entry)
                } else {
                    importPassword(entry)
                }
            }
            for (i in 0 until group.groupsCount) {
                processGroup(group.groups[i])
            }
        }

        processGroup(db.rootGroup)
    }

    private suspend fun importPassword(entry: org.linguafranca.pwdb.Entry<*, *, *, *>) {
        val websites = mutableListOf<String>()
        val url = entry.url.orEmpty()
        if (url.isNotEmpty()) websites.add(url)
        val extraWebsites = entry.getProperty("Websites")
        if (!extraWebsites.isNullOrEmpty()) {
            extraWebsites.split("\n").filter { it.isNotBlank() }.forEach { w ->
                if (w !in websites) websites.add(w)
            }
        }
        var totpSecret: String? = null
        val otp = entry.getProperty("otp")
        if (!otp.isNullOrEmpty()) {
            val match = Regex("[?&]secret=([^&]+)").find(otp)
            totpSecret = match?.groupValues?.get(1) ?: otp
        }
        if (totpSecret == null) {
            totpSecret = entry.getProperty("TOTP Seed")
        }

        val pw = Password(
            name = entry.title.orEmpty(),
            userId = entry.username.orEmpty(),
            password = entry.password.orEmpty(),
            websites = websites,
            totpSecret = totpSecret,
        )
        passwordDao.upsert(pw)
    }

    private suspend fun importPasskey(entry: org.linguafranca.pwdb.Entry<*, *, *, *>) {
        val privateKeyB64 = entry.getProperty("KPEX_PASSKEY_PRIVATE_KEY_PEM").orEmpty()
        val privateKeyBytes = if (privateKeyB64.isNotEmpty()) Base64.decode(privateKeyB64, Base64.NO_WRAP) else ByteArray(0)

        val pk = Passkey(
            rpId = entry.getProperty("KPEX_PASSKEY_RELYING_PARTY").orEmpty().ifEmpty { entry.url.orEmpty() },
            rpName = entry.title.orEmpty(),
            credentialId = entry.getProperty("KPEX_PASSKEY_CREDENTIAL_ID").orEmpty(),
            userId = entry.getProperty("KPEX_PASSKEY_USER_HANDLE").orEmpty(),
            userName = entry.getProperty("KPEX_PASSKEY_USERNAME").orEmpty().ifEmpty { entry.username.orEmpty() },
            userDisplayName = entry.username.orEmpty(),
            privateKeyBytes = privateKeyBytes,
        )
        passkeyDao.upsert(pk)
    }
}
