package com.vayunmathur.messages.whatsapp.e2e

import android.util.Log
import com.vayunmathur.messages.signal.store.SignalProtocolStoreImpl
import com.vayunmathur.messages.whatsapp.WhatsAppAuthData
import com.vayunmathur.messages.whatsapp.WhatsAppDatabase
import com.vayunmathur.messages.whatsapp.WhatsAppE2EPreKey
import com.vayunmathur.messages.whatsapp.WhatsAppProtocol
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.util.Base64
import java.util.UUID

/**
 * libsignal-backed E2E crypto helpers for WhatsApp, backed by [WhatsAppDatabase].
 * Mirrors whatsmeow's send.go/message.go/prekeys.go encryption paths.
 *
 * The own identity key pair is reconstructed from the raw 32-byte Curve25519 keys in
 * [WhatsAppAuthData]; one-time and signed prekeys are seeded into the protocol stores so
 * inbound pkmsg decryption can resolve them.
 */
class WhatsAppE2E(
    private val db: WhatsAppDatabase,
    private val auth: WhatsAppAuthData,
) {
    private val sessionStore = WhatsAppSessionStore(db)
    private val identityStore = WhatsAppIdentityKeyStore(
        db,
        b64(auth.identityPublicKey),
        b64(auth.identityPrivateKey),
        auth.registrationId,
    )
    private val preKeyStore = WhatsAppPreKeyStore(db)
    private val kyberStore = WhatsAppKyberPreKeyStore()
    private val senderKeyStore = WhatsAppSenderKeyStore(db)

    private val store = SignalProtocolStoreImpl(
        sessionStore, identityStore, preKeyStore, preKeyStore, kyberStore, senderKeyStore,
    )

    /** Raw 32-byte Curve25519 identity public key (for the prekey-upload identity node). */
    val ownIdentityPublicKey: ByteArray = b64(auth.identityPublicKey)

    private val ownAddress: SignalProtocolAddress = signalAddress(auth.wid, auth.deviceId)

    // -- Address mapping (whatsmeow JID.SignalAddress: name = user part, deviceId = device) --

    fun signalAddress(jid: String): SignalProtocolAddress {
        val local = jid.substringBefore("@")
        val user = local.substringBefore(":").substringBefore(".")
        val device = local.substringAfter(":", "0").toIntOrNull() ?: 0
        return SignalProtocolAddress(user, device)
    }

    private fun signalAddress(jid: String, device: Int): SignalProtocolAddress {
        val user = jid.substringBefore("@").substringBefore(":").substringBefore(".")
        return SignalProtocolAddress(user, device)
    }

    fun hasSession(jid: String): Boolean = sessionStore.containsSession(signalAddress(jid))

    // -- 1:1 (Signal session) encryption / decryption --

    data class EncResult(val type: String, val data: ByteArray)

    /** Encrypt a padded plaintext for a peer device. Returns enc type ("msg"|"pkmsg") + bytes. */
    fun encryptDM(jid: String, paddedPlaintext: ByteArray): EncResult {
        val cipher = SessionCipher(store, signalAddress(jid))
        val msg: CiphertextMessage = cipher.encrypt(paddedPlaintext)
        val type = when (msg.type) {
            CiphertextMessage.PREKEY_TYPE -> "pkmsg"
            else -> "msg"
        }
        return EncResult(type, msg.serialize())
    }

    /** Decrypt an inbound 1:1 ciphertext. [isPreKey] true for enc type "pkmsg". */
    fun decryptDM(jid: String, isPreKey: Boolean, ciphertext: ByteArray): ByteArray {
        val cipher = SessionCipher(store, signalAddress(jid))
        return if (isPreKey) {
            cipher.decrypt(PreKeySignalMessage(ciphertext))
        } else {
            cipher.decrypt(SignalMessage(ciphertext))
        }
    }

    /** Establish an outbound session from a fetched peer prekey bundle. */
    fun processPreKeyBundle(jid: String, bundle: PreKeyBundle) {
        val builder = SessionBuilder(store, signalAddress(jid))
        builder.process(bundle)
    }

    // -- Group (sender key) --
    // UNVERIFIED: WhatsApp's wire sender-key-name is the group JID; libsignal-client 0.86
    // keys sender keys by a UUID distributionId. We derive a deterministic distributionId
    // from the group JID. Interop with real WhatsApp is not runtime-tested.

    private fun distributionId(groupJid: String): UUID =
        UUID.nameUUIDFromBytes(groupJid.toByteArray(Charsets.UTF_8))

    /** Create our SenderKeyDistributionMessage for a group (to be wrapped in an SKDM E2E msg). */
    fun createSenderKeyDistribution(groupJid: String): SenderKeyDistributionMessage {
        val builder = GroupSessionBuilder(senderKeyStore)
        return builder.create(ownAddress, distributionId(groupJid))
    }

    /** Process an inbound SenderKeyDistributionMessage from a group participant. */
    fun processSenderKeyDistribution(senderJid: String, skdmBytes: ByteArray) {
        val builder = GroupSessionBuilder(senderKeyStore)
        builder.process(signalAddress(senderJid), SenderKeyDistributionMessage(skdmBytes))
    }

    /** Encrypt a padded plaintext as a group skmsg using our own sender key. */
    fun encryptGroup(groupJid: String, paddedPlaintext: ByteArray): ByteArray {
        val cipher = GroupCipher(senderKeyStore, ownAddress)
        return cipher.encrypt(distributionId(groupJid), paddedPlaintext).serialize()
    }

    /** Decrypt an inbound group skmsg from a participant. */
    fun decryptGroup(senderJid: String, ciphertext: ByteArray): ByteArray {
        val cipher = GroupCipher(senderKeyStore, signalAddress(senderJid))
        return cipher.decrypt(ciphertext)
    }

    // -- Prekey store seeding + upload --

    /**
     * Seed our own signed prekey (from auth) into the signed-prekey store so inbound pkmsg
     * decryption can resolve it. Idempotent.
     */
    fun ensureSignedPreKeyStored() {
        if (preKeyStore.containsSignedPreKey(auth.signedPreKeyId)) return
        val keyPair = ECKeyPair(
            ECPublicKey.fromPublicKeyBytes(b64(auth.signedPreKeyPublic)),
            ECPrivateKey(b64(auth.signedPreKeyPrivate)),
        )
        val record = SignedPreKeyRecord(
            auth.signedPreKeyId,
            System.currentTimeMillis(),
            keyPair,
            b64(auth.signedPreKeySignature),
        )
        preKeyStore.storeSignedPreKey(auth.signedPreKeyId, record)
    }

    /** Generate [count] one-time prekeys, persist them (unuploaded), and return their records. */
    fun generatePreKeys(count: Int): List<PreKeyRecord> {
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        val records = ArrayList<PreKeyRecord>(count)
        val entities = ArrayList<WhatsAppE2EPreKey>(count)
        for (i in 1..count) {
            val id = maxId + i
            val record = PreKeyRecord(id, ECKeyPair.generate())
            records.add(record)
            entities.add(WhatsAppE2EPreKey(id, record.serialize(), uploaded = false))
        }
        runBlocking { db.e2ePreKeyDao().insertAll(entities) }
        return records
    }

    /**
     * Build the <iq xmlns="encrypt" type="set"> content nodes uploading identity,
     * registration, one-time prekeys and the signed prekey. Ref whatsmeow prekeys.go.
     */
    fun buildPreKeyUploadContent(initialUpload: Boolean): List<WhatsAppProtocol.Node> {
        ensureSignedPreKeyStored()
        val wanted = if (initialUpload) 812 else 50
        val records = generatePreKeys(wanted)

        val regBytes = ByteArray(4)
        regBytes[0] = (auth.registrationId ushr 24).toByte()
        regBytes[1] = (auth.registrationId ushr 16).toByte()
        regBytes[2] = (auth.registrationId ushr 8).toByte()
        regBytes[3] = auth.registrationId.toByte()

        val listNode = WhatsAppProtocol.Node(
            tag = "list",
            content = records.map { preKeyToNode(it.id, it.keyPair.publicKey.publicKeyBytes, null) },
        )
        val signedNode = preKeyToNode(
            auth.signedPreKeyId,
            b64(auth.signedPreKeyPublic),
            b64(auth.signedPreKeySignature),
        )

        return listOf(
            WhatsAppProtocol.Node(tag = "registration", data = regBytes),
            WhatsAppProtocol.Node(tag = "type", data = byteArrayOf(0x05)), // ecc.DjbType
            WhatsAppProtocol.Node(tag = "identity", data = ownIdentityPublicKey),
            listNode,
            signedNode,
        )
    }

    /** Mark prekeys up to the highest uploaded id as uploaded. */
    fun markPreKeysUploaded() {
        val maxId = runBlocking { db.e2ePreKeyDao().getMaxId() }
        runBlocking { db.e2ePreKeyDao().markUploadedUpTo(maxId) }
    }

    /**
     * Build the <keys> node included in a retry receipt (identity, one fresh one-time prekey, the
     * signed prekey and the account device-identity). Ref whatsmeow retry.go sendRetryReceipt.
     */
    fun buildRetryReceiptKeysNode(accountDeviceIdentity: ByteArray?): WhatsAppProtocol.Node {
        ensureSignedPreKeyStored()
        val oneTime = generatePreKeys(1).first()
        val children = mutableListOf(
            WhatsAppProtocol.Node(tag = "type", data = byteArrayOf(0x05)),
            WhatsAppProtocol.Node(tag = "identity", data = ownIdentityPublicKey),
            preKeyToNode(oneTime.id, oneTime.keyPair.publicKey.publicKeyBytes, null),
            preKeyToNode(auth.signedPreKeyId, b64(auth.signedPreKeyPublic), b64(auth.signedPreKeySignature)),
        )
        if (accountDeviceIdentity != null) {
            children.add(WhatsAppProtocol.Node(tag = "device-identity", data = accountDeviceIdentity))
        }
        return WhatsAppProtocol.Node(tag = "keys", content = children)
    }

    private fun preKeyToNode(id: Int, pub32: ByteArray, signature: ByteArray?): WhatsAppProtocol.Node {
        // key id is sent as 3 big-endian bytes (whatsmeow keyID[1:])
        val idBytes = byteArrayOf(
            (id ushr 16).toByte(),
            (id ushr 8).toByte(),
            id.toByte(),
        )
        val children = mutableListOf(
            WhatsAppProtocol.Node(tag = "id", data = idBytes),
            WhatsAppProtocol.Node(tag = "value", data = pub32),
        )
        return if (signature != null) {
            children.add(WhatsAppProtocol.Node(tag = "signature", data = signature))
            WhatsAppProtocol.Node(tag = "skey", content = children)
        } else {
            WhatsAppProtocol.Node(tag = "key", content = children)
        }
    }

    /**
     * Parse a peer's <user> node from a prekey-fetch response into a [PreKeyBundle].
     * Ref whatsmeow prekeys.go nodeToPreKeyBundle. Returns null on malformed/error nodes.
     */
    fun parsePreKeyBundleNode(deviceId: Int, userNode: WhatsAppProtocol.Node): PreKeyBundle? {
        return try {
            if (userNode.getChildByTag("error") != null) {
                Log.w(TAG, "prekey response error for device $deviceId")
                return null
            }
            val regBytes = userNode.getChildByTag("registration")?.data ?: return null
            if (regBytes.size != 4) return null
            val registrationId = ((regBytes[0].toInt() and 0xFF) shl 24) or
                ((regBytes[1].toInt() and 0xFF) shl 16) or
                ((regBytes[2].toInt() and 0xFF) shl 8) or
                (regBytes[3].toInt() and 0xFF)

            val keysNode = userNode.getChildByTag("keys") ?: userNode

            val identityRaw = keysNode.getChildByTag("identity")?.data ?: return null
            if (identityRaw.size != 32) return null
            val identityKey = IdentityKey(ECPublicKey.fromPublicKeyBytes(identityRaw))

            var preKeyId = -1
            var preKeyPublic: ECPublicKey? = null
            keysNode.getChildByTag("key")?.let { keyNode ->
                preKeyId = readKeyId(keyNode) ?: return null
                val pub = keyNode.getChildByTag("value")?.data ?: return null
                preKeyPublic = ECPublicKey.fromPublicKeyBytes(pub)
            }

            val skey = keysNode.getChildByTag("skey") ?: return null
            val signedPreKeyId = readKeyId(skey) ?: return null
            val signedPub = skey.getChildByTag("value")?.data ?: return null
            val signedSig = skey.getChildByTag("signature")?.data ?: return null

            // kyberPreKeyId = -1 -> bridging treats kyber as absent (classic X3DH); the dummy
            // KEMPublicKey only satisfies the non-null Kotlin type. Same pattern as signal module.
            PreKeyBundle(
                registrationId, deviceId,
                preKeyId, preKeyPublic,
                signedPreKeyId, ECPublicKey.fromPublicKeyBytes(signedPub), signedSig,
                identityKey,
                -1,
                org.signal.libsignal.protocol.kem.KEMPublicKey(ByteArray(1568) { if (it == 0) 0x07 else 0x00 }),
                ByteArray(0),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse prekey bundle", e)
            null
        }
    }

    private fun readKeyId(node: WhatsAppProtocol.Node): Int? {
        val idBytes = node.getChildByTag("id")?.data ?: return null
        if (idBytes.size != 3) return null
        return ((idBytes[0].toInt() and 0xFF) shl 16) or
            ((idBytes[1].toInt() and 0xFF) shl 8) or
            (idBytes[2].toInt() and 0xFF)
    }

    companion object {
        private const val TAG = "WhatsAppE2E"

        private fun b64(s: String): ByteArray = Base64.getDecoder().decode(s)

        /**
         * Curve25519 signature over 0x05||signedPreKeyPub using the raw 32-byte identity
         * private key. Ref whatsmeow KeyPair.Sign. Used to populate signedPreKeySignature.
         */
        fun signSignedPreKey(identityPrivate32: ByteArray, signedPreKeyPublic32: ByteArray): ByteArray {
            val message = ByteArray(33)
            message[0] = 0x05
            System.arraycopy(signedPreKeyPublic32, 0, message, 1, 32)
            return ECPrivateKey(identityPrivate32).calculateSignature(message)
        }
    }
}
