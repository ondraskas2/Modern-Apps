package com.vayunmathur.messages.signal.store

import kotlinx.coroutines.runBlocking
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import java.util.UUID

class SignalSenderKeyStore(private val db: SignalDatabase) : SenderKeyStore {

    override fun storeSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
        record: SenderKeyRecord,
    ) {
        runBlocking {
            db.senderKeyDao().insert(
                SignalSenderKeyEntity(sender.name, distributionId.toString(), record.serialize())
            )
        }
    }

    override fun loadSenderKey(
        sender: SignalProtocolAddress,
        distributionId: UUID,
    ): SenderKeyRecord? {
        val entity = runBlocking {
            db.senderKeyDao().get(sender.name, distributionId.toString())
        } ?: return null
        return SenderKeyRecord(entity.record)
    }
}
