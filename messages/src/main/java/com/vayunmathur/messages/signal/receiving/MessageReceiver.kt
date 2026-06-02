package com.vayunmathur.messages.signal.receiving

import android.util.Log
import com.vayunmathur.messages.signal.proto.WebSocketProtos
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.groups.state.SenderKeyStore

class MessageReceiver(
    private val sessionStore: SessionStore,
    private val identityKeyStore: IdentityKeyStore,
    private val preKeyStore: PreKeyStore,
    private val signedPreKeyStore: SignedPreKeyStore,
    private val kyberPreKeyStore: KyberPreKeyStore,
    private val senderKeyStore: SenderKeyStore,
    private val selfAci: String,
    private val deviceId: Int,
    private val onDecrypted: (DecryptedMessage) -> Unit,
) {
    fun handleRequest(request: WebSocketProtos.WebSocketRequestMessage) {
        if (request.verb != "PUT" || request.path != "/api/v1/message") {
            Log.d(TAG, "Ignoring request: ${request.verb} ${request.path}")
            return
        }

        val envelope = try {
            SignalServiceProtos.Envelope.parseFrom(request.body)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse envelope", e)
            return
        }

        val result = EnvelopeDecryptor.decrypt(
            envelope = envelope,
            sessionStore = sessionStore,
            identityKeyStore = identityKeyStore,
            preKeyStore = preKeyStore,
            signedPreKeyStore = signedPreKeyStore,
            kyberPreKeyStore = kyberPreKeyStore,
            senderKeyStore = senderKeyStore,
            certificateValidator = null,
            selfAci = selfAci,
            selfDeviceId = deviceId,
        )

        if (result.error != null) {
            Log.e(TAG, "Decryption failed from ${result.senderAci}:${result.senderDeviceId}", result.error)
            return
        }

        val content = result.content
        if (content == null) {
            Log.d(TAG, "No content (server delivery receipt) from ${result.senderAci}")
            onDecrypted(
                DecryptedMessage(
                    senderAci = result.senderAci,
                    senderDeviceId = result.senderDeviceId,
                    timestamp = result.timestamp,
                    serverTimestamp = result.serverTimestamp,
                    content = MessageContent.DeliveryReceipt(timestamps = listOf(result.timestamp)),
                )
            )
            return
        }

        val message = ContentDispatcher.dispatch(
            senderAci = result.senderAci,
            senderDeviceId = result.senderDeviceId,
            content = content,
            timestamp = result.timestamp,
            serverTimestamp = result.serverTimestamp,
        )
        onDecrypted(message)
    }

    companion object {
        private const val TAG = "SignalReceiver"
    }
}
