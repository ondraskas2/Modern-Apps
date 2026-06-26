package com.vayunmathur.messages.signal.store

import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

/**
 * Pre-key store scoped to a single Signal service identity (ACI or PNI). Each service keeps its
 * own independent key-id namespace, mirroring signalmeow's per-service scoped stores
 * (store/prekey_store.go: queries filter on service_id). This replaces the previous +1000000
 * id-offset hack: that hack stored the PNI signed/last-resort keys at offset ids locally while the
 * server knew them under id=1, so a PNI-addressed pre-key message (referencing signed-pre-key id=1)
 * would load the ACI key and fail to decrypt.
 */
class SignalPreKeyStore(
    private val db: SignalDatabase,
    private val service: String = SERVICE_ACI,
) : PreKeyStore, SignedPreKeyStore, KyberPreKeyStore {

    companion object {
        const val SERVICE_ACI = "ACI"
        const val SERVICE_PNI = "PNI"
    }

    // PreKeyStore

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val entity = runBlocking { db.preKeyDao().get(service, preKeyId) }
            ?: throw InvalidKeyIdException("No pre key: $preKeyId")
        return PreKeyRecord(entity.record)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        runBlocking { db.preKeyDao().insert(SignalPreKeyEntity(service, preKeyId, record.serialize())) }
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return runBlocking { db.preKeyDao().exists(service, preKeyId) }
    }

    override fun removePreKey(preKeyId: Int) {
        runBlocking { db.preKeyDao().delete(service, preKeyId) }
    }

    // SignedPreKeyStore

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val entity = runBlocking { db.signedPreKeyDao().get(service, signedPreKeyId) }
            ?: throw InvalidKeyIdException("No signed pre key: $signedPreKeyId")
        return SignedPreKeyRecord(entity.record)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        return runBlocking { db.signedPreKeyDao().getAll(service) }.map { SignedPreKeyRecord(it.record) }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        runBlocking {
            db.signedPreKeyDao().insert(SignalSignedPreKeyEntity(service, signedPreKeyId, record.serialize()))
        }
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return runBlocking { db.signedPreKeyDao().exists(service, signedPreKeyId) }
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        runBlocking { db.signedPreKeyDao().delete(service, signedPreKeyId) }
    }

    // KyberPreKeyStore

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val entity = runBlocking { db.kyberPreKeyDao().get(service, kyberPreKeyId) }
            ?: throw InvalidKeyIdException("No kyber pre key: $kyberPreKeyId")
        return KyberPreKeyRecord(entity.record)
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
        return runBlocking { db.kyberPreKeyDao().getAll(service) }.map { KyberPreKeyRecord(it.record) }
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        runBlocking {
            db.kyberPreKeyDao().insert(
                SignalKyberPreKeyEntity(service, kyberPreKeyId, false, record.serialize())
            )
        }
    }

    fun storeLastResortKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        runBlocking {
            db.kyberPreKeyDao().insert(
                SignalKyberPreKeyEntity(service, kyberPreKeyId, true, record.serialize())
            )
        }
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
        return runBlocking { db.kyberPreKeyDao().exists(service, kyberPreKeyId) }
    }

    fun removeKyberPreKey(kyberPreKeyId: Int) {
        runBlocking { db.kyberPreKeyDao().delete(service, kyberPreKeyId) }
    }

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, publicKey: org.signal.libsignal.protocol.ecc.ECPublicKey) {
        val isLastResort = runBlocking { db.kyberPreKeyDao().isLastResort(service, kyberPreKeyId) } ?: return
        if (!isLastResort) {
            runBlocking { db.kyberPreKeyDao().delete(service, kyberPreKeyId) }
        }
    }

    fun isKyberPreKeyLastResort(kyberPreKeyId: Int): Boolean {
        return runBlocking { db.kyberPreKeyDao().isLastResort(service, kyberPreKeyId) } ?: false
    }

    fun loadLastResortKyberPreKeys(): List<KyberPreKeyRecord> {
        return runBlocking { db.kyberPreKeyDao().getAll(service) }
            .filter { it.lastResort }
            .map { KyberPreKeyRecord(it.record) }
    }

    fun getAllPreKeys(): List<PreKeyRecord> {
        return runBlocking { db.preKeyDao().getAll(service) }.map { PreKeyRecord(it.record) }
    }

    fun getAllNormalKyberPreKeys(): List<KyberPreKeyRecord> {
        return runBlocking { db.kyberPreKeyDao().getAllNonLastResort(service) }
            .map { KyberPreKeyRecord(it.record) }
    }

    fun getNextPreKeyId(): Pair<Int, Int> {
        val count = runBlocking { db.preKeyDao().getCount(service) }
        val maxId = runBlocking { db.preKeyDao().getMaxId(service) }
        return Pair(count, maxId + 1)
    }

    fun getNextKyberPreKeyId(): Pair<Int, Int> {
        val count = runBlocking { db.kyberPreKeyDao().getCount(service) }
        val maxId = runBlocking { db.kyberPreKeyDao().getMaxId(service) }
        return Pair(count, maxId + 1)
    }

    fun deleteAllPreKeys() {
        runBlocking {
            db.preKeyDao().deleteAll(service)
            db.signedPreKeyDao().deleteAll(service)
            db.kyberPreKeyDao().deleteAll(service)
        }
    }
}
