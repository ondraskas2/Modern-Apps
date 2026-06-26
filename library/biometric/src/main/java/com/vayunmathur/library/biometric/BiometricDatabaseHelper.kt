package com.vayunmathur.library.biometric

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.util.DatabaseHelper
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

class BiometricDatabaseHelper(context: Context) : DatabaseHelper(context) {
    override val keyStoreAlias = "db_auth_key"
    override val passphraseKey = "encrypted_passphrase"
    override val ivKey = "passphrase_iv"

    override fun generateKey() {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            keyStoreAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(false)

        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }
}

fun unlockDatabaseWithBiometrics(
    activity: FragmentActivity,
    onSuccess: (String) -> Unit,
    onFailure: () -> Unit
) {
    val helper = BiometricDatabaseHelper(activity)
    val executor = ContextCompat.getMainExecutor(activity)

    fun prompt(title: String, subtitle: String, cipher: Cipher, onCipher: (Cipher) -> String) {
        val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess(onCipher(result.cryptoObject?.cipher!!))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailure()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    if (!helper.isKeyGenerated()) {
        helper.generateKey()
        prompt(
            "Setup Secure Database",
            "Authenticate to create your secure encryption key",
            helper.getCipherForEncryption()
        ) { helper.createAndStorePassphrase(it) }
    } else {
        prompt(
            "Unlock Database",
            "Authenticate to access your secure data",
            helper.getCipherForDecryption()
        ) { helper.decryptPassphrase(it) }
    }
}
