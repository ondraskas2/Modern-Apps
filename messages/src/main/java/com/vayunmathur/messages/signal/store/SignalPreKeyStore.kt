package com.vayunmathur.messages.signal.store

import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.KyberPreKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyStore

class SignalPreKeyStore(private val db: SignalDatabase) : PreKeyStore, SignedPreKeyStore, KyberPreKeyStore {

    // PreKeyStore

    override fun loadPreKey(preKeyId: Int): PreKeyRecord {
        val entity = runBlocking { db.preKeyDao().get(preKeyId) }
            ?: throw InvalidKeyIdException("No pre key: $preKeyId")
        return PreKeyRecord(entity.record)
    }

    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        runBlocking { db.preKeyDao().insert(SignalPreKeyEntity(preKeyId, record.serialize())) }
    }

    override fun containsPreKey(preKeyId: Int): Boolean {
        return runBlocking { db.preKeyDao().exists(preKeyId) }
    }

    override fun removePreKey(preKeyId: Int) {
        runBlocking { db.preKeyDao().delete(preKeyId) }
    }

    // SignedPreKeyStore

    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord {
        val entity = runBlocking { db.signedPreKeyDao().get(signedPreKeyId) }
            ?: throw InvalidKeyIdException("No signed pre key: $signedPreKeyId")
        return SignedPreKeyRecord(entity.record)
    }

    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> {
        return runBlocking { db.signedPreKeyDao().getAll() }.map { SignedPreKeyRecord(it.record) }
    }

    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        runBlocking {
            db.signedPreKeyDao().insert(SignalSignedPreKeyEntity(signedPreKeyId, record.serialize()))
        }
    }

    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean {
        return runBlocking { db.signedPreKeyDao().exists(signedPreKeyId) }
    }

    override fun removeSignedPreKey(signedPreKeyId: Int) {
        runBlocking { db.signedPreKeyDao().delete(signedPreKeyId) }
    }

    // KyberPreKeyStore

    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord {
        val entity = runBlocking { db.kyberPreKeyDao().get(kyberPreKeyId) }
            ?: throw InvalidKeyIdException("No kyber pre key: $kyberPreKeyId")
        return KyberPreKeyRecord(entity.record)
    }

    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> {
        return runBlocking { db.kyberPreKeyDao().getAll() }.map { KyberPreKeyRecord(it.record) }
    }

    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        runBlocking {
            db.kyberPreKeyDao().insert(
                SignalKyberPreKeyEntity(kyberPreKeyId, false, record.serialize())
            )
        }
    }

    fun storeLastResortKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        runBlocking {
            db.kyberPreKeyDao().insert(
                SignalKyberPreKeyEntity(kyberPreKeyId, true, record.serialize())
            )
        }
    }

    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean {
        return runBlocking { db.kyberPreKeyDao().exists(kyberPreKeyId) }
    }

    fun removeKyberPreKey(kyberPreKeyId: Int) {
        runBlocking { db.kyberPreKeyDao().delete(kyberPreKeyId) }
    }

    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, publicKey: org.signal.libsignal.protocol.ecc.ECPublicKey) {
        val entity = runBlocking { db.kyberPreKeyDao().get(kyberPreKeyId) } ?: return
        if (!entity.lastResort) {
            runBlocking { db.kyberPreKeyDao().delete(kyberPreKeyId) }
        }
    }

    fun loadLastResortKyberPreKeys(): List<KyberPreKeyRecord> {
        return runBlocking { db.kyberPreKeyDao().getAll() }
            .filter { it.lastResort }
            .map { KyberPreKeyRecord(it.record) }
    }
}
