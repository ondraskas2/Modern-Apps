package com.vayunmathur.messages.signal.receiving

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.store.SignalProtocolStoreImpl
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.certificate.CertificateValidator
import java.util.UUID

object EnvelopeDecryptor {

    private const val TAG = "SignalReceiver"
    private const val CONTENT_HINT_RESENDABLE = 1
    private val SIGNAL_SERVER_TRUST_ROOT = Base64.decode(
        "BXu4Sr3OzuDoeeFab3yc3LaWGPsyVDKzp12qlMQDa2B1hQ==", Base64.DEFAULT
    )

    data class DecryptionResult(
        val senderAci: String,
        val senderDeviceId: Int,
        val content: SignalServiceProtos.Content?,
        val timestamp: Long,
        val serverTimestamp: Long,
        val error: Throwable? = null,
        val senderE164: String? = null,
        val unidentified: Boolean = false,
        val retriable: Boolean = false,
        val unencrypted: Boolean = false,
        val contentHint: Int = 0,
        val groupId: ByteArray? = null,
        val ciphertext: ByteArray? = null,
        val ciphertextType: Int = 0,
    )

    fun decrypt(
        envelope: SignalServiceProtos.Envelope,
        sessionStore: SessionStore,
        identityKeyStore: IdentityKeyStore,
        preKeyStore: PreKeyStore,
        signedPreKeyStore: SignedPreKeyStore,
        kyberPreKeyStore: KyberPreKeyStore,
        senderKeyStore: SenderKeyStore,
        certificateValidator: CertificateValidator?,
        selfAci: String,
        selfDeviceId: Int,
    ): DecryptionResult {
        val senderAci = envelope.sourceServiceId ?: ""
        val senderDeviceId = envelope.sourceDeviceId
        val timestamp = envelope.clientTimestamp
        val serverTimestamp = envelope.serverTimestamp
        val destinationServiceId = envelope.destinationServiceId ?: ""

        if (destinationServiceId.isEmpty()) {
            return DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                error = IllegalArgumentException("Envelope missing destination service ID"))
        }

        val protocolStore = SignalProtocolStoreImpl(
            sessionStore, identityKeyStore, preKeyStore, signedPreKeyStore, kyberPreKeyStore, senderKeyStore
        )

        return try {
            when (envelope.type) {
                SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET -> {
                    val address = SignalProtocolAddress(senderAci, senderDeviceId)
                    val cipher = SessionCipher(protocolStore, address)
                    try {
                        val plaintext = cipher.decrypt(SignalMessage(envelope.content.toByteArray()))
                        val content = SignalServiceProtos.Content.parseFrom(stripPadding(plaintext))
                        DecryptionResult(senderAci, senderDeviceId, content, timestamp, serverTimestamp)
                    } catch (e: Exception) {
                        DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                            error = e, retriable = true,
                            ciphertext = envelope.content.toByteArray(),
                            ciphertextType = CiphertextMessage.WHISPER_TYPE)
                    }
                }

                SignalServiceProtos.Envelope.Type.PREKEY_MESSAGE -> {
                    val address = SignalProtocolAddress(senderAci, senderDeviceId)
                    val cipher = SessionCipher(protocolStore, address)
                    try {
                        val plaintext = cipher.decrypt(PreKeySignalMessage(envelope.content.toByteArray()))
                        val content = SignalServiceProtos.Content.parseFrom(stripPadding(plaintext))
                        DecryptionResult(senderAci, senderDeviceId, content, timestamp, serverTimestamp)
                    } catch (e: Exception) {
                        DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                            error = e, retriable = true,
                            ciphertext = envelope.content.toByteArray(),
                            ciphertextType = CiphertextMessage.PREKEY_TYPE)
                    }
                }

                SignalServiceProtos.Envelope.Type.UNIDENTIFIED_SENDER -> {
                    if (destinationServiceId != selfAci) {
                        Log.w(TAG, "Received UNIDENTIFIED_SENDER envelope for non-ACI destination: $destinationServiceId")
                        return DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                            error = IllegalArgumentException("Received unidentified sender envelope for non-ACI destination"))
                    }
                    val sealedCipher = SealedSessionCipher(
                        protocolStore, UUID.fromString(selfAci), selfAci, selfDeviceId
                    )
                    val trustRoot = ECPublicKey(SIGNAL_SERVER_TRUST_ROOT)
                    val validator = certificateValidator
                        ?: CertificateValidator(listOf(trustRoot))
                    val result = sealedCipher.decrypt(validator, envelope.content.toByteArray(), serverTimestamp)
                    val content = SignalServiceProtos.Content.parseFrom(stripPadding(result.paddedMessage))
                    DecryptionResult(
                        senderAci = result.senderUuid,
                        senderDeviceId = result.deviceId,
                        content = content,
                        timestamp = timestamp,
                        serverTimestamp = serverTimestamp,
                        senderE164 = result.senderE164.orElse(null),
                        unidentified = true,
                        contentHint = 0,
                        groupId = result.groupId.orElse(null),
                    )
                }

                SignalServiceProtos.Envelope.Type.PLAINTEXT_CONTENT -> {
                    val plaintext = stripPadding(envelope.content.toByteArray())
                    val content = SignalServiceProtos.Content.newBuilder()
                        .setDecryptionErrorMessage(com.google.protobuf.ByteString.copyFrom(plaintext))
                        .build()
                    DecryptionResult(senderAci, senderDeviceId, content, timestamp, serverTimestamp,
                        unencrypted = true)
                }

                SignalServiceProtos.Envelope.Type.SERVER_DELIVERY_RECEIPT -> {
                    DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                        error = IllegalArgumentException("Server delivery receipt envelopes are not yet supported"))
                }

                else -> {
                    Log.w(TAG, "Unknown envelope type: ${envelope.type}")
                    DecryptionResult(senderAci, senderDeviceId, null, timestamp, serverTimestamp,
                        error = IllegalArgumentException("Unknown envelope type: ${envelope.type}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error for type ${envelope.type}", e)
            val resolvedSender = senderAci
            val resolvedDeviceId = senderDeviceId
            if (envelope.type == SignalServiceProtos.Envelope.Type.UNIDENTIFIED_SENDER) {
                return DecryptionResult(
                    senderAci = resolvedSender,
                    senderDeviceId = resolvedDeviceId,
                    content = null, timestamp = timestamp, serverTimestamp = serverTimestamp,
                    error = e,
                    retriable = false,
                    ciphertext = envelope.content.toByteArray(),
                    ciphertextType = CiphertextMessage.SENDERKEY_TYPE,
                )
            }
            DecryptionResult(resolvedSender, resolvedDeviceId, null, timestamp, serverTimestamp, error = e)
        }
    }

    private fun stripPadding(padded: ByteArray): ByteArray {
        var i = padded.size - 1
        while (i >= 0 && padded[i] == 0.toByte()) i--
        if (i < 0) throw IllegalArgumentException("invalid ISO7816 padding (length ${padded.size})")
        if (padded[i] == 0x80.toByte()) return padded.copyOfRange(0, i)
        throw IllegalArgumentException("invalid ISO7816 padding")
    }
}
