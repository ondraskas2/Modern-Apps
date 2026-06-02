package com.vayunmathur.messages.signal.store

import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SessionStore

class SignalSessionStore(private val db: SignalDatabase) : SessionStore {

    override fun loadSession(address: SignalProtocolAddress): SessionRecord {
        val entity = runBlocking { db.sessionDao().get(address.name, address.deviceId) }
        return if (entity != null) SessionRecord(entity.record) else SessionRecord()
    }

    override fun getSubDeviceSessions(name: String): List<Int> {
        return runBlocking { db.sessionDao().getSubDeviceIds(name) }
    }

    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        runBlocking {
            db.sessionDao().insert(
                SignalSessionEntity(address.name, address.deviceId, record.serialize())
            )
        }
    }

    override fun containsSession(address: SignalProtocolAddress): Boolean {
        return runBlocking { db.sessionDao().exists(address.name, address.deviceId) }
    }

    override fun deleteSession(address: SignalProtocolAddress) {
        runBlocking { db.sessionDao().delete(address.name, address.deviceId) }
    }

    override fun loadExistingSessions(addresses: MutableList<SignalProtocolAddress>): List<SessionRecord> {
        return addresses.mapNotNull { addr ->
            runBlocking { db.sessionDao().get(addr.name, addr.deviceId) }?.let { SessionRecord(it.record) }
        }
    }

    override fun deleteAllSessions(name: String) {
        runBlocking { db.sessionDao().deleteAll(name) }
    }
}
