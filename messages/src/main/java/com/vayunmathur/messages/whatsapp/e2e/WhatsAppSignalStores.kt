package com.vayunmathur.messages.whatsapp.e2e

import com.vayunmathur.messages.whatsapp.WhatsAppDatabase
import com.vayunmathur.messages.whatsapp.WhatsAppE2EIdentity
import com.vayunmathur.messages.whatsapp.WhatsAppE2EPreKey
import com.vayunmathur.messages.whatsapp.WhatsAppE2ESenderKey
import com.vayunmathur.messages.whatsapp.WhatsAppE2ESession
import com.vayunmathur.messages.whatsapp.WhatsAppE2ESignedPreKey
import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore
import java.util.UUID

/**
 * libsignal protocol stores backed by [WhatsAppDatabase]. Mirrors the signal module's
 * store pattern (runBlocking bridge from suspend DAO -> synchronous libsignal callbacks).
 * The composed [org.signal.libsignal.protocol.state.SignalProtocolStore] is assembled in
 * [WhatsAppE2E].
 *
 * UNVERIFIED: runBlocking inside libsignal's synchronous store callbacks is the same
 * approach the signal module uses; correctness is assumed but not runtime-tested here.
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

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): List<SessionRecord> {
        return addresses.mapNotNull { addr ->
            runBlocking { db.e2eSessionDao().get(addr.name, addr.deviceId) }?.let { SessionRecord(it.record) }
        }
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
        IdentityKey(ECPublicKey.fromPublicKeyBytes(identityPublicKey)),
        ECPrivateKey(identityPrivateKey),
    )

    override fun getIdentityKeyPair(): IdentityKeyPair = identityKeyPair

    override fun getLocalRegistrationId(): Int = localRegistrationId

    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val existing = runBlocking { db.e2eIdentityDao().get(address.name) }
        runBlocking {
            db.e2eIdentityDao().insert(WhatsAppE2EIdentity(address.name, identityKey.serialize()))
        }
        return if (existing != null && !existing.identityKey.contentEquals(identityKey.serialize())) {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        } else {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        }
    }

    // UNVERIFIED: WhatsApp uses trust-on-first-use; we always trust like whatsmeow's
    // AutoTrustIdentity behaviour.
    override fun isTrustedIdentity(
        address: SignalProtocolAddress,
        identityKey: IdentityKey,
        direction: IdentityKeyStore.Direction,
    ): Boolean = true

    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? {
        val entity = runBlocking { db.e2eIdentityDao().get(address.name) } ?: return null
        return IdentityKey(entity.identityKey)
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

/**
 * Minimal no-op Kyber store. WhatsApp's classic (non-LID-PQ) bundle has no Kyber prekey,
 * so [PreKeyBundle] is always built with NULL_PRE_KEY_ID and never queries these.
 */
class WhatsAppKyberPreKeyStore : KyberPreKeyStore {
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        throw InvalidKeyIdException("No kyber pre key: $kyberPreKeyId")

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = emptyList()

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {}

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = false

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, publicKey: ECPublicKey) {}
}

class WhatsAppSenderKeyStore(private val db: WhatsAppDatabase) : SenderKeyStore {

    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        runBlocking {
            db.e2eSenderKeyDao().insert(
                WhatsAppE2ESenderKey(sender.name, sender.deviceId, distributionId.toString(), record.serialize())
            )
        }
    }

    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? {
        val entity = runBlocking {
            db.e2eSenderKeyDao().get(sender.name, sender.deviceId, distributionId.toString())
        } ?: return null
        return SenderKeyRecord(entity.record)
    }
}
