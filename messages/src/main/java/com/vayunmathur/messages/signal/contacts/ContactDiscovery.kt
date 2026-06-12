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

class ContactDiscoveryRateLimitError(val retryAfterSeconds: Long) : Exception(
    "contact discovery rate limited for ${retryAfterSeconds}s"
)

data class CDSResponseEntry(val aci: UUID, val pni: UUID)

class ContactDiscovery(
    private val recipientStore: SignalRecipientStore,
    private val ws: com.vayunmathur.messages.signal.web.SignalWebSocket,
    private val context: android.content.Context,
) {
    private var cachedCdsiUsername: String? = null
    private var cachedCdsiPassword: String? = null
    private var cdsiAuthExpiry: Long = 0L
    private var cdsiToken: ByteArray? = null

    suspend fun lookupPhones(vararg e164s: Long): Map<Long, CDSResponseEntry>? {
        if (e164s.isEmpty()) return null
        val requestData = ByteBuffer.allocate(e164s.size * 8)
        for (e164 in e164s) requestData.putLong(e164)
        val (cdsiUsername, cdsiPassword) = getCdsiAuth() ?: return null
        val (resp, token) = performCdsiLookup(requestData.array(), cdsiUsername, cdsiPassword)
            ?: return null
        if (token != null) cdsiToken = token
        return resp
    }

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
        val e164Num = e164.removePrefix("+").toLong()
        val results = try {
            lookupPhones(e164Num)
        } catch (e: Exception) {
            Log.e(TAG, "CDSI lookup failed for $e164", e)
            null
        } ?: return null
        val entry = results[e164Num] ?: return null
        val nilUUID = UUID(0, 0)
        val aci = entry.aci.takeIf { it != nilUUID }?.toString()
        val pni = entry.pni.takeIf { it != nilUUID }?.toString()
        if (aci != null) {
            recipientStore.storeRecipient(SignalRecipientEntity(aci = aci, e164 = e164))
            Log.d(TAG, "CDSI resolved $e164 -> ACI $aci")
            return aci
        } else if (pni != null) {
            val pniWithPrefix = "PNI:$pni"
            recipientStore.storeRecipient(SignalRecipientEntity(aci = pniWithPrefix, e164 = e164))
            Log.d(TAG, "CDSI resolved $e164 -> PNI $pni (no ACI)")
            return pniWithPrefix
        }
        return null
    }

    private suspend fun getCdsiAuth(): Pair<String, String>? {
        val now = System.currentTimeMillis()
        val username = cachedCdsiUsername
        val password = cachedCdsiPassword
        if (username != null && password != null && now < cdsiAuthExpiry) {
            return username to password
        }
        val authResponse = ws.sendRequest(
            "GET",
            "/v2/directory/auth",
        )
        if (authResponse.status !in 200..299) {
            Log.e(TAG, "Directory auth failed: ${authResponse.status}")
            return null
        }
        val authJson = JSONObject(authResponse.body.toStringUtf8())
        val newUsername = authJson.getString("username")
        val newPassword = authJson.getString("password")
        cachedCdsiUsername = newUsername
        cachedCdsiPassword = newPassword
        cdsiAuthExpiry = now + CDSI_AUTH_TTL_MS
        return newUsername to newPassword
    }

    private suspend fun performCdsiLookup(
        newE164sData: ByteArray,
        cdsiUsername: String,
        cdsiPassword: String,
    ): Pair<Map<Long, CDSResponseEntry>, ByteArray?>? {
        val url = "wss://$CDSI_HOST/v1/$MRENCLAVE/discovery"
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
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code == RATE_LIMIT_CLOSE_CODE) {
                    val retryAfter = try {
                        JSONObject(reason).optLong("retry_after", 0)
                    } catch (_: Exception) { 0L }
                    connected.completeExceptionally(ContactDiscoveryRateLimitError(retryAfter))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "CDSI WebSocket failure", t)
                connected.completeExceptionally(t)
            }
        })

        return try {
            withTimeout(20000) {
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

                val cdsiRequest = CDSClientRequest.newBuilder()
                    .setNewE164S(com.google.protobuf.ByteString.copyFrom(newE164sData))
                if (cdsiToken != null) {
                    cdsiRequest.setToken(com.google.protobuf.ByteString.copyFrom(cdsiToken))
                }
                val encryptedReq = cds2Client.establishedSend(cdsiRequest.build().toByteArray())
                socket.send(encryptedReq.toByteString())

                // Response loop matching Go's ReadResponse
                var token: ByteArray? = null
                var response: Map<Long, CDSResponseEntry>? = null
                while (response == null) {
                    val msg = messages.poll(10, TimeUnit.SECONDS)
                        ?: throw IllegalStateException("No CDSI response")
                    val decrypted = cds2Client.establishedRecv(msg)
                    val cdsiResp = CDSClientResponse.parseFrom(decrypted)

                    if (cdsiResp.hasToken()) {
                        token = cdsiResp.token.toByteArray()
                        val tokenAck = CDSClientRequest.newBuilder()
                            .setTokenAck(true).build()
                        val encAck = cds2Client.establishedSend(tokenAck.toByteArray())
                        socket.send(encAck.toByteString())
                    }

                    if (!cdsiResp.e164PniAciTriples.isEmpty) {
                        response = parseTriples(cdsiResp.e164PniAciTriples.toByteArray())
                    }
                }
                Pair(response, token)
            }
        } finally {
            socket?.close(3000, "Normal")
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun parseTriples(triples: ByteArray): Map<Long, CDSResponseEntry> {
        val tripleSize = 8 + 16 + 16
        val pairCount = triples.size / tripleSize
        if (pairCount * tripleSize != triples.size) {
            throw IllegalStateException("Invalid response size ${triples.size} (not divisible by $tripleSize)")
        }
        val result = mutableMapOf<Long, CDSResponseEntry>()
        for (i in 0 until pairCount) {
            val offset = i * tripleSize
            val e164 = ByteBuffer.wrap(triples, offset, 8).long
            if (e164 == 0L) continue
            val pniBuf = ByteBuffer.wrap(triples, offset + 8, 16)
            val pni = UUID(pniBuf.long, pniBuf.long)
            val aciBuf = ByteBuffer.wrap(triples, offset + 24, 16)
            val aci = UUID(aciBuf.long, aciBuf.long)
            result[e164] = CDSResponseEntry(aci = aci, pni = pni)
        }
        return result
    }

    companion object {
        private const val TAG = "ContactDiscovery"
        private const val CDSI_HOST = "cdsi.signal.org"
        private const val MRENCLAVE = "15637fa1e54fe655176d3df1a9f94b87c01ed377acaa570682dc5d72c95ef07b"
        private const val CDSI_AUTH_TTL_MS = 23 * 60 * 60 * 1000L
        private const val RATE_LIMIT_CLOSE_CODE = 4008
    }
}
