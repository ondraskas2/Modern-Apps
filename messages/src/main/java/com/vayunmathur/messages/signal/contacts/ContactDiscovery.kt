package com.vayunmathur.messages.signal.contacts

import android.util.Log
import com.vayunmathur.messages.signal.store.SignalRecipientEntity
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import com.vayunmathur.messages.signal.web.CertPinning
import com.vayunmathur.messages.signal.web.SignalHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import org.signal.libsignal.cds2.Cds2Client
import signalservice.ContactDiscovery.CDSClientRequest
import signalservice.ContactDiscovery.CDSClientResponse
import java.nio.ByteBuffer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Contact Discovery Service (CDSI) implementation matching Go signalmeow.
 * Uses manual WebSocket + CDS2 handshake to support return_acis_without_uaks flag.
 */
class ContactDiscovery(
    private val recipientStore: SignalRecipientStore,
    private val selfAci: String,
    private val selfDeviceId: Int,
    private val selfPassword: String,
    private val context: android.content.Context,
) {
    suspend fun resolveE164(e164: String): String? {
        val cached = recipientStore.getByE164(e164)
        if (cached != null) {
            // Handle legacy cached entries that may have <PNI:uuid> or <ACI:uuid> format
            val aci = cached.aci
                .removePrefix("<").removeSuffix(">")
                .let { 
                    if (it.startsWith("PNI:") || it.startsWith("ACI:")) it.substringAfter(":") else it
                }
            Log.d(TAG, "Resolved $e164 from local store: $aci")
            return aci
        }
        return lookupViaCdsi(e164)?.let { result ->
            val aci = result.aci
            val pni = result.pni
            if (aci != null) {
                recipientStore.storeRecipient(
                    SignalRecipientEntity(aci = aci, e164 = e164, profileName = null)
                )
                Log.d(TAG, "CDSI resolved $e164 -> ACI $aci")
                aci
            } else if (pni != null) {
                val pniWithPrefix = "PNI:$pni"
                recipientStore.storeRecipient(
                    SignalRecipientEntity(aci = pniWithPrefix, e164 = e164, profileName = null)
                )
                Log.d(TAG, "CDSI resolved $e164 -> PNI $pni (no ACI)")
                pniWithPrefix
            } else {
                null
            }
        }
    }

    private data class CdsResult(val aci: String?, val pni: String?)

    private suspend fun lookupViaCdsi(e164: String): CdsResult? {
        return try {
            val authResponse = SignalHttpClient.request(
                method = "GET",
                path = "/v2/directory/auth",
                username = "$selfAci.$selfDeviceId",
                password = selfPassword,
            )
            if (!authResponse.isSuccessful) {
                Log.e(TAG, "Directory auth failed: ${authResponse.code}")
                return null
            }
            val authJson = JSONObject(authResponse.body?.string() ?: return null)
            val cdsiUsername = authJson.getString("username")
            val cdsiPassword = authJson.getString("password")
            Log.d(TAG, "Got CDSI auth credentials")
            performCdsiLookup(e164, cdsiUsername, cdsiPassword)
        } catch (e: Exception) {
            Log.e(TAG, "CDSI lookup failed for $e164", e)
            null
        }
    }

    private suspend fun performCdsiLookup(
        e164: String,
        cdsiUsername: String,
        cdsiPassword: String,
    ): CdsResult? {
        val url = "wss://cdsi.signal.org/v1/$MRENCLAVE/discovery"
        val (sslSocketFactory, trustManager) = CertPinning.createSslSocketFactory(context)
        val client = OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(cdsiUsername, cdsiPassword))
            .build()

        val messages = LinkedBlockingQueue<ByteArray>()
        val connected = CompletableDeferred<Unit>()

        var socket: WebSocket? = null
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.complete(Unit)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                messages.put(bytes.toByteArray())
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "CDSI WebSocket failure", t)
                connected.completeExceptionally(t)
            }
        })

        return try {
            withTimeout(15000) {
                connected.await()
                val attestationMsg = messages.poll(10, TimeUnit.SECONDS)
                    ?: throw IllegalStateException("No attestation")
                val mrenclave = MRENCLAVE.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val cds2Client = Cds2Client(mrenclave, attestationMsg, Instant.now())
                val initialRequest = cds2Client.initialRequest()
                socket.send(initialRequest.toByteString())
                val handshakeFinish = messages.poll(10, TimeUnit.SECONDS)
                    ?: throw IllegalStateException("No handshake finish")
                cds2Client.completeHandshake(handshakeFinish)

                val e164Num = e164.removePrefix("+").toLong()
                val e164Bytes = ByteBuffer.allocate(8).putLong(e164Num).array()
                val cdsiRequest = CDSClientRequest.newBuilder()
                    .setNewE164S(com.google.protobuf.ByteString.copyFrom(e164Bytes))
                    .setReturnAcisWithoutUaks(true)
                    .build()
                val encryptedRequest = cds2Client.establishedSend(cdsiRequest.toByteArray())
                socket.send(encryptedRequest.toByteString())

                val encryptedResponse = messages.poll(10, TimeUnit.SECONDS)
                    ?: throw IllegalStateException("No CDSI response")
                val decryptedResponse = cds2Client.establishedRecv(encryptedResponse)
                val cdsiResponse = CDSClientResponse.parseFrom(decryptedResponse)

                if (cdsiResponse.hasToken()) {
                    val tokenAck = CDSClientRequest.newBuilder().setTokenAck(true).build()
                    val encryptedAck = cds2Client.establishedSend(tokenAck.toByteArray())
                    socket.send(encryptedAck.toByteString())
                }

                val triples = cdsiResponse.e164PniAciTriples.toByteArray()
                if (triples.size < 40) return@withTimeout null

                val pniBytes = triples.copyOfRange(8, 24)
                val aciBytes = triples.copyOfRange(24, 40)
                val pni = if (pniBytes.any { it != 0.toByte() }) {
                    val buf = ByteBuffer.wrap(pniBytes)
                    UUID(buf.long, buf.long).toString()
                } else null
                val aci = if (aciBytes.any { it != 0.toByte() }) {
                    val buf = ByteBuffer.wrap(aciBytes)
                    UUID(buf.long, buf.long).toString()
                } else null

                if (aci != null || pni != null) CdsResult(aci, pni) else null
            }
        } finally {
            socket?.close(1000, "Done")
            client.dispatcher.executorService.shutdown()
        }
    }

    companion object {
        private const val TAG = "ContactDiscovery"
        private const val MRENCLAVE = "15637fa1e54fe655176d3df1a9f94b87c01ed377acaa570682dc5d72c95ef07b"
    }
}
