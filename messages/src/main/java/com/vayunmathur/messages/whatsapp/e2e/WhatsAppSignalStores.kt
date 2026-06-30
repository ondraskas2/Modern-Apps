package com.vayunmathur.messages.whatsapp.e2e

import com.vayunmathur.messages.whatsapp.WhatsAppDatabase
import com.vayunmathur.messages.whatsapp.WhatsAppE2EIdentity
import com.vayunmathur.messages.whatsapp.WhatsAppE2EPreKey
import com.vayunmathur.messages.whatsapp.WhatsAppE2ESenderKey
import com.vayunmathur.messages.whatsapp.WhatsAppE2ESession
import com.vayunmathur.messages.whatsapp.WhatsAppE2ESignedPreKey
import kotlinx.coroutines.runBlocking
import org.whispersystems.libsignal.IdentityKey
import org.whispersystems.libsignal.IdentityKeyPair
import org.whispersystems.libsignal.InvalidKeyIdException
import org.whispersystems.libsignal.SignalProtocolAddress
import org.whispersystems.libsignal.ecc.Curve
import org.whispersystems.libsignal.groups.SenderKeyName
import org.whispersystems.libsignal.groups.state.SenderKeyRecord
import org.whispersystems.libsignal.groups.state.SenderKeyStore
import org.whispersystems.libsignal.state.IdentityKeyStore
import org.whispersystems.libsignal.state.PreKeyRecord
import org.whispersystems.libsignal.state.PreKeyStore
import org.whispersystems.libsignal.state.SessionRecord
import org.whispersystems.libsignal.state.SessionStore
import org.whispersystems.libsignal.state.SignedPreKeyRecord
import org.whispersystems.libsignal.state.SignedPreKeyStore

/**
 * Classic (whispersystems) libsignal protocol stores backed by [WhatsAppDatabase]. WhatsApp
 * companion sessions use Signal protocol v3 (X3DH), which org.signal:libsignal-android 0.86
 * no longer supports, so the WhatsApp bridge uses the pure-Java org.whispersystems library.
 *
 * runBlocking bridges the suspend Room DAOs into libsignal's synchronous store callbacks.
 */
class WhatsAppSessionStore(private val db: WhatsAppDatabase) : SessionStore {

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val entity = runBlocking { db.e2eSessionDao().get(address.name, address.deviceId) }
        return if (entity != null) SessionRecord(entity.record) else SessionRecord()
    }

    override fun getSubDeviceSessions(name: String): List<Int> {
        return runBlocking { db.e2eSessionDao().getSubDeviceIds(name) }
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        runBlocking {
            db.e2eSessionDao().insert(
                WhatsAppE2ESession(address.name, address.deviceId, record.serialize())
            )
        }
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return runBlocking { db.e2eSessionDao().exists(address.name, address.deviceId) }
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        runBlocking { db.e2eSessionDao().delete(address.name, address.deviceId) }
    }

    override fun deleteAllSessions(name: String) {
        runBlocking { db.e2eSessionDao().deleteAll(name) }
    }
}

class WhatsAppIdentityKeyStore(
    private val db: WhatsAppDatabase,
    identityPublicKey: ByteArray,
    identityPrivateKey: ByteArray,
    private val localRegistrationId: Int,
) : IdentityKeyStore {

    // Own identity built from the raw 32-byte Curve25519 key pair stored in auth.
    private val identityKeyPair: IdentityKeyPair = IdentityKeyPair(
        IdentityKey(Curve.decodePoint(byteArrayOf(0x05) + identityPublicKey, 0)),
        Curve.decodePrivatePoint(identityPrivateKey),
    )

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = localRegistrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): Boolean {
        val existing = runBlocking { db.e2eIdentityDao().get(address.name) }
        runBlocking {
            db.e2eIdentityDao().insert(WhatsAppE2EIdentity(address.name, identityKey.serialize()))
        }
        return existing != null && !existing.identityKey.contentEquals(identityKey.serialize())
    }

    // WhatsApp uses trust-on-first-use; always trust like whatsmeow's AutoTrustIdentity.
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val entity = runBlocking { db.e2eIdentityDao().get(address.name) } ?: return null
        return IdentityKey(entity.identityKey, 0)
    }
}

class WhatsAppPreKeyStore(private val db: WhatsAppDatabase) : PreKeyStore, SignedPreKeyStore {

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val entity = runBlocking { db.e2ePreKeyDao().get(preKeyId) }
            ?: throw InvalidKeyIdException("No pre key: $preKeyId")
        return PreKeyRecord(entity.record)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        runBlocking { db.e2ePreKeyDao().insert(WhatsAppE2EPreKey(preKeyId, record.serialize())) }
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return runBlocking { db.e2ePreKeyDao().exists(preKeyId) }
    }

    // whatsmeow removes a one-time prekey once it has been consumed by an incoming pkmsg.
    override fun removePreKey(preKeyId: Int) {
        runBlocking { db.e2ePreKeyDao().delete(preKeyId) }
    }

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val entity = runBlocking { db.e2eSignedPreKeyDao().get(signedPreKeyId) }
            ?: throw InvalidKeyIdException("No signed pre key: $signedPreKeyId")
        return SignedPreKeyRecord(entity.record)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        return runBlocking { db.e2eSignedPreKeyDao().getAll() }.map { SignedPreKeyRecord(it.record) }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        runBlocking { db.e2eSignedPreKeyDao().insert(WhatsAppE2ESignedPreKey(signedPreKeyId, record.serialize())) }
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return runBlocking { db.e2eSignedPreKeyDao().exists(signedPreKeyId) }
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        runBlocking { db.e2eSignedPreKeyDao().delete(signedPreKeyId) }
    }
}

class WhatsAppSenderKeyStore(private val db: WhatsAppDatabase) : SenderKeyStore {

    override fun storeSenderKey(senderKeyName: SenderKeyName, record: SenderKeyRecord) {
        val sender = senderKeyName.sender
        runBlocking {
            db.e2eSenderKeyDao().insert(
                WhatsAppE2ESenderKey(sender.name, sender.deviceId, senderKeyName.groupId, record.serialize())
            )
        }
    }

    override fun loadSenderKey(senderKeyName: SenderKeyName): SenderKeyRecord {
        val sender = senderKeyName.sender
        val entity = runBlocking {
            db.e2eSenderKeyDao().get(sender.name, sender.deviceId, senderKeyName.groupId)
        } ?: return SenderKeyRecord()
        return SenderKeyRecord(entity.record)
    }
}
