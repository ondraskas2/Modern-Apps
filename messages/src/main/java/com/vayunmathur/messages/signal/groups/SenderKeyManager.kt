package com.vayunmathur.messages.signal.groups

import android.util.Log
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.groups.GroupCipher
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage
import org.signal.libsignal.protocol.groups.state.SenderKeyStore
import java.util.UUID

class SenderKeyManager(
    private val senderKeyStore: SenderKeyStore,
    private val selfAci: String,
    private val selfDeviceId: Int,
) {
    private val distributedTo = mutableMapOf<String, MutableSet<String>>()

    fun createDistributionMessage(groupId: String): SenderKeyDistributionMessage {
        val selfAddress = SignalProtocolAddress(selfAci, selfDeviceId)
        val distributionId = UUID.nameUUIDFromBytes(groupId.toByteArray())
        val builder = GroupSessionBuilder(senderKeyStore)
        return builder.create(selfAddress, distributionId)
    }

    fun needsDistribution(groupId: String, memberAci: String): Boolean {
        val distributed = distributedTo[groupId] ?: return true
        return memberAci !in distributed
    }

    fun markDistributed(groupId: String, memberAci: String) {
        distributedTo.getOrPut(groupId) { mutableSetOf() }.add(memberAci)
    }

    fun resetForGroup(groupId: String) {
        distributedTo.remove(groupId)
        Log.d(TAG, "Reset sender key distribution for group $groupId")
    }

    companion object {
        private const val TAG = "SenderKeyManager"
    }
}
