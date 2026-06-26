package com.vayunmathur.messages.meta

import android.util.Log

/**
 * DB SyncManager (#10). Ports messagix/syncManager.go: it tracks per-database
 * sync state (sync params / sync channel / last-applied cursor) and per
 * sync-group key-store ranges, drives socket-based DB syncs, and recurses on
 * cursors until each database is fully caught up.
 *
 * It is transport-agnostic: callers supply a [LSRequester] that performs an
 * /ls_req round-trip and returns the raw response, plus an [EventSink] that the
 * decoded events are pushed through so the normal handler path ingests them.
 *
 * // UNVERIFIED: the recursion / cursor handling mirrors the Go reference but
 * // cannot be exercised without a live authenticated socket.
 */
class MetaSyncManager(
    private val platform: MetaAuthData.Platform,
    @Volatile var versionId: Long,
    private val syncParamsMailbox: String,
    private val syncParamsContact: String,
    private val syncParamsE2ee: String,
) {
    companion object {
        private const val TAG = "MetaSyncManager"

        // Databases that should keep recursing while a fresh cursor is returned.
        private val SHOULD_RECURSE = setOf(1L, 2L, 95L, 104L)

        // Sync sets per platform (socket.go).
        private val MINIMAL_INITIAL_SYNC = listOf(1L)
        private val MINIMAL_FB_INITIAL_SYNC = listOf(1L, 104L)
        private val MINIMAL_RECONNECT_SYNC = listOf(1L, 2L)
        private val MINIMAL_FB_RECONNECT_SYNC = listOf(1L, 2L, 104L)

        private const val DB7_PARAMS = """{"mnet_rank_types":[44]}"""
    }

    data class QueryMetadata(
        var sendSyncParams: Boolean,
        var syncChannel: Long,
        var lastAppliedCursor: String? = null,
    )

    data class KeyStoreData(
        var parentThreadKey: Long = -1,
        var minLastActivityTimestampMs: Long = 9999999999999,
        var hasMoreBefore: Boolean = false,
        var minThreadKey: Long = 0,
    )

    /** Performs an /ls_req round trip and returns the raw response (or null). */
    fun interface LSRequester {
        suspend fun request(payload: String, type: Int): MetaProtocol.MqttMessage?
    }

    private val store: MutableMap<Long, QueryMetadata> = linkedMapOf(
        1L to QueryMetadata(sendSyncParams = false, syncChannel = MetaProtocol.SYNC_CHANNEL_MAILBOX),
        2L to QueryMetadata(sendSyncParams = true, syncChannel = MetaProtocol.SYNC_CHANNEL_CONTACT),
        5L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        6L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        7L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        16L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        26L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        28L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        89L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        95L to QueryMetadata(sendSyncParams = false, syncChannel = MetaProtocol.SYNC_CHANNEL_CONTACT),
        104L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        120L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        140L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        141L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        142L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        143L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        145L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        196L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        197L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        198L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
        202L to QueryMetadata(sendSyncParams = true, syncChannel = 0),
    )

    private val keyStore: MutableMap<Long, KeyStoreData> = linkedMapOf(
        1L to KeyStoreData(),
        95L to KeyStoreData(),
    )

    fun reconnectSyncSet(): List<Long> =
        if (platform == MetaAuthData.Platform.MESSENGER) MINIMAL_FB_RECONNECT_SYNC else MINIMAL_RECONNECT_SYNC

    fun initialSyncSet(): List<Long> =
        if (platform == MetaAuthData.Platform.MESSENGER) MINIMAL_FB_INITIAL_SYNC else MINIMAL_INITIAL_SYNC

    fun getCursor(db: Long): String = store[db]?.lastAppliedCursor ?: ""

    fun getKeyStore(syncGroup: Long): KeyStoreData? = keyStore[syncGroup]

    /** Ensures the given databases are synced via the socket. Ref EnsureSyncedSocket. */
    suspend fun ensureSynced(databases: List<Long>, requester: LSRequester) {
        for (db in databases) {
            val meta = store[db]
            if (meta == null) {
                Log.e(TAG, "Could not find sync store for database $db")
                continue
            }
            try {
                syncSocketData(db, meta, requester)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync database $db through socket", e)
            }
        }
    }

    private suspend fun syncSocketData(
        databaseId: Long,
        meta: QueryMetadata,
        requester: LSRequester,
    ) {
        val prevCursor = meta.lastAppliedCursor
        val type: Int
        val payload: String = if (meta.sendSyncParams) {
            type = MetaProtocol.LS_REQUEST_TYPE_DB_QUERY
            MetaProtocol.buildDatabaseSyncPayload(
                databaseId = databaseId,
                versionId = versionId,
                sendSyncParams = true,
                syncParams = getSyncParams(databaseId, meta.syncChannel),
                cursor = null,
            )
        } else {
            type = MetaProtocol.LS_REQUEST_TYPE_DB_QUERY_CURSOR
            MetaProtocol.buildDatabaseSyncPayload(
                databaseId = databaseId,
                versionId = versionId,
                sendSyncParams = false,
                syncParams = null,
                cursor = meta.lastAppliedCursor,
            )
        }

        val response = requester.request(payload, type) ?: run {
            Log.w(TAG, "No response syncing database $databaseId")
            return
        }
        val responseData = MetaProtocol.parsePublishResponse(response.payload) ?: return
        val events = LightspeedDecoder.decodePublishResponse(responseData.payload, responseData.sp)

        updateSyncGroupCursors(events)

        val blocks = MetaProtocol.parseSyncTransactions(events)
        if (blocks.isEmpty()) {
            Log.d(TAG, "No transactions found for database $databaseId")
            return
        }
        val block = blocks.first()
        val nextCursor = block.nextCursor
        if (nextCursor == block.currentCursor ||
            nextCursor == prevCursor ||
            nextCursor == "dummy_cursor" ||
            nextCursor.isEmpty() ||
            databaseId !in SHOULD_RECURSE
        ) {
            return
        }

        meta.lastAppliedCursor = nextCursor
        meta.sendSyncParams = block.sendSyncParams
        if (block.syncChannel != 0L) meta.syncChannel = block.syncChannel
        syncSocketData(databaseId, meta, requester)
    }

    /** Updates cursors/ranges from a decoded response. Ref updateSyncGroupCursors. */
    fun updateSyncGroupCursors(events: List<LightspeedDecoder.DecodedEvent>) {
        val ranges = MetaProtocol.parseSyncGroupRanges(events)
        if (ranges.isNotEmpty()) updateThreadRanges(ranges)

        val blocks = MetaProtocol.parseSyncTransactions(events)
        for (block in blocks) {
            val meta = store[block.databaseId] ?: continue
            meta.lastAppliedCursor = block.nextCursor
            meta.sendSyncParams = block.sendSyncParams
            if (block.syncChannel != 0L) meta.syncChannel = block.syncChannel
        }
    }

    private fun updateThreadRanges(ranges: List<MetaProtocol.SyncGroupRange>) {
        for (range in ranges) {
            if (!range.hasMoreBefore) continue
            val ks = keyStore[range.syncGroup]
            if (ks == null) {
                Log.w(TAG, "Could not find keyStore for sync group ${range.syncGroup}")
                continue
            }
            ks.hasMoreBefore = range.hasMoreBefore
            ks.minLastActivityTimestampMs = range.minLastActivityTimestampMs
            ks.minThreadKey = range.minThreadKey
            ks.parentThreadKey = range.parentThreadKey
        }
    }

    private fun getSyncParams(dbId: Long, channel: Long): String {
        if (dbId == 7L) return DB7_PARAMS
        return when (channel) {
            MetaProtocol.SYNC_CHANNEL_MAILBOX -> syncParamsMailbox
            MetaProtocol.SYNC_CHANNEL_CONTACT -> syncParamsContact
            else -> syncParamsE2ee
        }
    }
}
