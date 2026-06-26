package com.vayunmathur.findfamily.util
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.LocationValueCompatible
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.UserDao
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.util.DataStoreUtils
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA512
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

object Networking {
    private const val URL = "https://findfamily.cc"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val crypto = CryptographyProvider.Default.get(RSA.OAEP)
    private var publickey: RSA.OAEP.PublicKey? = null
    private var privatekey: RSA.OAEP.PrivateKey? = null
    private var network_is_down = false

    var userid = 0L
        private set

    private lateinit var userDao: UserDao
    private lateinit var dataStoreUtils: DataStoreUtils

    suspend fun init(userDao: UserDao, dataStoreUtils: DataStoreUtils, meName: String) {
        Networking.dataStoreUtils = dataStoreUtils
        Networking.userDao = userDao
        val keyPair = crypto.keyPairGenerator(digest = SHA512).generateKey()
        dataStoreUtils.setByteArray("privateKey", keyPair.privateKey.encodeToByteArray(RSA.PrivateKey.Format.PEM), true)
        dataStoreUtils.setByteArray("publicKey", keyPair.publicKey.encodeToByteArray(RSA.PublicKey.Format.PEM), true)
        dataStoreUtils.setLong("userid", Random.nextLong(), true)

        publickey = crypto.publicKeyDecoder(SHA512).decodeFromByteArray(RSA.PublicKey.Format.PEM, dataStoreUtils.getByteArray("publicKey")!!)
        privatekey = crypto.privateKeyDecoder(SHA512).decodeFromByteArray(RSA.PrivateKey.Format.PEM, dataStoreUtils.getByteArray("privateKey")!!)
        userid = dataStoreUtils.getLong("userid")!!

        if (userDao.getById(userid) == null) {
            userDao.upsert(
                User(
                    meName,
                    null,
                    "Unnamed Location",
                    true,
                    RequestStatus.MUTUAL_CONNECTION,
                    Clock.System.now(),
                    null,
                    userid,
                )
            )
        }
    }

    private suspend fun <T> checkNetworkDown(makeRequest: suspend ()->T?): T? {
        try {
            val x = makeRequest()
            network_is_down = false
            return x
        } catch (e: CancellationException) {
            throw e
        } catch(e: Exception) {
            network_is_down = true
        }
        return null
    }

    private suspend inline fun <reified T, reified I> makeRequest(path: String, body: I): T? {
        return checkNetworkDown {
            try {
                NetworkClient.callJson<T>(
                    url = "$URL$path",
                    method = "POST",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = body
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun register(): Boolean {
        @Serializable
        data class Register(val userid: ULong, val key: String)
        return makeRequest<Boolean, Register>("/api/register", Register(
            userid.toULong(),
            Base64.encode(publickey!!.encodeToByteArray(RSA.PublicKey.Format.PEM))
        )
        ) ?: false
    }

    suspend fun ensureUserExists() {
        if(getKey(userid) == null) {
            register()
        }
    }

    private suspend fun getKey(userid: Long): RSA.OAEP.PublicKey? {
        return checkNetworkDown {
            val response = NetworkClient.performRequest(
                url = "$URL/api/getkey",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(GetKeyRequest(userid.toULong()))
            )
            if(response.status != 200) {
                return@checkNetworkDown null
            }
            return@checkNetworkDown crypto.publicKeyDecoder(SHA512).decodeFromByteArray(RSA.PublicKey.Format.PEM,
                Base64.decode(response.body)
            )
        }
    }

    /** Local platform tag included in outgoing heartbeat payloads so the peer learns we're on Android. */
    private const val PLATFORM = "android"

    suspend fun publishLocation(location: LocationValue, user: User): Boolean {
        val key = if(user.encryptionKey != null) {
            crypto.publicKeyDecoder(SHA512).decodeFromByteArray(RSA.PublicKey.Format.PEM,
                Base64.decode(user.encryptionKey)
            )
        } else {
            getKey(user.id)?.also {
                val keyString = Base64.encode(it.encodeToByteArray(RSA.PublicKey.Format.PEM))
                userDao.upsert(user.copy(encryptionKey = keyString))
            }
        } ?: return false
        return makeRequest<Boolean, LocationSharingData>("/api/location/publish", encryptLocation(location, user.id, key)) ?: false
    }

    suspend fun publishLocation(location: LocationValue, user: TemporaryLink): Boolean {
        val key = crypto.publicKeyDecoder(SHA512).decodeFromByteArray(RSA.PublicKey.Format.PEM,
            Base64.decode(user.publicKey)
        )
        return makeRequest<Boolean, LocationSharingData>("/api/location/publish", encryptLocation(location, user.id, key)) ?: false
    }

    suspend fun receiveLocations(): List<LocationValue>? {
        val strings: List<String>? = makeRequest("/api/location/receive", json.encodeToString(UserIdRequest(userid)))
        return strings?.map { decryptLocation(it) }?.also { decoded ->
            // Opportunistically update peer platform tags learned from incoming heartbeats.
            decoded.forEach { (loc, platform) ->
                if (platform != null) {
                    val u = userDao.getById(loc.userid)
                    if (u != null && u.platform != platform) {
                        userDao.upsert(u.copy(platform = platform))
                    }
                }
            }
        }?.map { it.first }
    }

    // ----------------------------------------------------------------
    // UWB session-setup channel
    //
    // Mirrors the location publish/receive flow but carries the small UWB
    // handshake envelopes (request / ack / config / cancel) end-to-end
    // encrypted. Each payload is at most a few hundred bytes; ranging samples
    // themselves never touch the server.
    // ----------------------------------------------------------------

    suspend fun publishUwbMessage(envelope: UwbEnvelope, recipientUserId: Long, recipient: User? = null): Boolean {
        val key = if (recipient?.encryptionKey != null) {
            crypto.publicKeyDecoder(SHA512).decodeFromByteArray(
                RSA.PublicKey.Format.PEM,
                Base64.decode(recipient.encryptionKey)
            )
        } else {
            getKey(recipientUserId) ?: return false
        }
        val cipher = key.encryptor()
        val str = json.encodeToString(envelope)
        val encryptedData = Base64.encode(cipher.encrypt(str.encodeToByteArray()))
        // We can't reuse `makeRequest<Boolean, ...>` here because the server
        // returns `204 No Content` (no body) on success, and Ktor's content
        // negotiation throws `NoTransformationFoundException` trying to
        // deserialize an empty body into a `Boolean`. `performRequest` returns
        // the raw response and we treat any 2xx status as success.
        val bodyJson = json.encodeToString(LocationSharingData(recipientUserId.toULong(), encryptedData))
        return checkNetworkDown {
            val resp = NetworkClient.performRequest(
                url = "$URL/api/uwb/publish",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = bodyJson
            )
            resp.status in 200..299
        } ?: false
    }

    /**
     * Drains incoming UWB envelopes addressed to this user. The server queue
     * is cleared on receive (same semantics as `/api/location/receive`).
     */
    suspend fun receiveUwbMessages(): List<UwbEnvelope>? {
        val strings: List<String>? = makeRequest("/api/uwb/receive", json.encodeToString(UserIdRequest(userid)))
        val cipher = privatekey?.decryptor() ?: return null
        return strings?.mapNotNull { b64 ->
            runCatching {
                val plain = cipher.decrypt(Base64.decode(b64)).decodeToString()
                json.decodeFromString<UwbEnvelope>(plain)
            }.getOrNull()
        }
    }

    private suspend fun encryptLocation(location: LocationValue, recipientUserID: Long, key: RSA.OAEP.PublicKey): LocationSharingData {
        val cipher = key.encryptor()
        val str = json.encodeToString(location.toCompatible(senderPlatform = PLATFORM))
        val encryptedData = Base64.encode(cipher.encrypt(str.encodeToByteArray()))
        return LocationSharingData(recipientUserID.toULong(), encryptedData)
    }

    private suspend fun decryptLocation(encryptedLocation: String): Pair<LocationValue, String?> {
        val cipher = privatekey!!.decryptor()
        val decryptedData = cipher.decrypt(Base64.decode(encryptedLocation)).decodeToString()
        val compat = json.decodeFromString<LocationValueCompatible>(decryptedData)
        return compat.toLocationValue() to compat.senderPlatform
    }

    suspend fun generateKeyPair(): RSA.OAEP.KeyPair {
        return crypto.keyPairGenerator(digest = SHA512).generateKey()
    }

    @Serializable
    private data class LocationSharingData(val recipientUserID: ULong, val encryptedLocation: String)

    @Serializable
    private data class UserIdRequest(val userid: Long)

    @Serializable
    private data class GetKeyRequest(val userid: ULong)
}
