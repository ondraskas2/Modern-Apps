package com.vayunmathur.messages.signal.store

class SignalGroupStore(private val db: SignalDatabase) {

    suspend fun getGroup(groupId: String): SignalGroupEntity? {
        return db.groupDao().get(groupId)
    }

    suspend fun storeGroup(entity: SignalGroupEntity) {
        db.groupDao().insert(entity)
    }

    suspend fun getAllGroups(): List<SignalGroupEntity> {
        return db.groupDao().getAll()
    }
}
