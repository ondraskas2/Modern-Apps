package com.vayunmathur.messages.signal.groups

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.Group
import com.vayunmathur.messages.signal.proto.GroupAttributeBlob
import com.vayunmathur.messages.signal.proto.GroupResponse
import com.vayunmathur.messages.signal.store.SignalGroupEntity
import com.vayunmathur.messages.signal.store.SignalGroupStore
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import com.vayunmathur.messages.signal.web.SignalHttpClient
import com.vayunmathur.messages.signal.web.SignalWebSocket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.signal.libsignal.zkgroup.ServerPublicParams
import org.signal.libsignal.zkgroup.auth.AuthCredentialWithPniResponse
import org.signal.libsignal.zkgroup.auth.ClientZkAuthOperations
import org.signal.libsignal.zkgroup.groups.GroupMasterKey
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.libsignal.protocol.ServiceId

class GroupManager(
    private val ws: SignalWebSocket,
    private val groupStore: SignalGroupStore,
    private val recipientStore: SignalRecipientStore,
    private val aci: String,
    private val pni: String,
    private val password: String,
) {
    

    // Issue #8: Set group name via PATCH to groups API
    suspend fun setGroupName(groupId: String, newName: String): Boolean {
        return try {
            val stored = groupStore.getGroup(groupId) ?: return false
            val masterKey = stored.masterKey
            val auth = getGroupAuth(masterKey) ?: return false
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)

            val currentGroup = getOrFetchGroup(groupId, masterKey) ?: return false
            val newRevision = currentGroup.revision + 1

            val titleBlob = GroupAttributeBlob.newBuilder().setTitle(newName).build()
            val encryptedTitle = groupSecretParams.encryptBlobWithPadding(titleBlob.toByteArray())

            val actionsBuilder = com.vayunmathur.messages.signal.proto.GroupChange.Actions.newBuilder()
                .setVersion(newRevision)
                .setModifyTitle(
                    com.vayunmathur.messages.signal.proto.GroupChange.Actions.ModifyTitleAction.newBuilder()
                        .setTitle(com.google.protobuf.ByteString.copyFrom(encryptedTitle))
                )

            val response = SignalHttpClient.request(
                host = SignalHttpClient.STORAGE_HOST,
                method = "PATCH",
                path = "/v2/groups",
                body = actionsBuilder.build().toByteArray(),
                contentType = "application/x-protobuf",
                username = auth.username,
                password = auth.password,
            )

            if (response.code == 200) {
                invalidateCachedGroup(groupId)
                fetchGroup(groupId, masterKey)
                true
            } else {
                Log.e(TAG, "setGroupName failed with status ${response.code}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "setGroupName failed for $groupId", e)
            false
        }
    }

    // Issue #9: Invite a member (AddPendingMemberAction)
    suspend fun inviteMember(groupId: String, serviceId: String, role: MemberRole = MemberRole.DEFAULT): Boolean {
        return try {
            val stored = groupStore.getGroup(groupId) ?: return false
            val masterKey = stored.masterKey
            val auth = getGroupAuth(masterKey) ?: return false
            val currentGroup = getOrFetchGroup(groupId, masterKey) ?: return false
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)

            val serviceIdObj = ServiceId.Aci(UUID.fromString(serviceId))
            val encryptedUserId = groupSecretParams.encryptServiceId(serviceIdObj)

            val ownServiceId = ServiceId.Aci(UUID.fromString(aci))
            val encryptedAddedBy = groupSecretParams.encryptServiceId(ownServiceId)

            val pendingMember = com.vayunmathur.messages.signal.proto.MemberPendingProfileKey.newBuilder()
                .setMember(
                    com.vayunmathur.messages.signal.proto.Member.newBuilder()
                        .setUserId(com.google.protobuf.ByteString.copyFrom(encryptedUserId))
                        .setRole(com.vayunmathur.messages.signal.proto.Member.Role.forNumber(role.value))
                )
                .setAddedByUserId(com.google.protobuf.ByteString.copyFrom(encryptedAddedBy))
                .setTimestamp(System.currentTimeMillis())

            val actions = com.vayunmathur.messages.signal.proto.GroupChange.Actions.newBuilder()
                .setVersion(currentGroup.revision + 1)
                .addAddPendingMembers(
                    com.vayunmathur.messages.signal.proto.GroupChange.Actions.AddPendingMemberAction.newBuilder()
                        .setAdded(pendingMember)
                )

            submitGroupChange(groupId, masterKey, auth, actions.build())
        } catch (e: Exception) {
            Log.e(TAG, "inviteMember failed", e)
            false
        }
    }

    // Issue #9: Kick a member (DeleteMemberAction)
    suspend fun kickMember(groupId: String, memberAci: String): Boolean {
        return try {
            val stored = groupStore.getGroup(groupId) ?: return false
            val masterKey = stored.masterKey
            val auth = getGroupAuth(masterKey) ?: return false
            val currentGroup = getOrFetchGroup(groupId, masterKey) ?: return false
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)

            val serviceId = ServiceId.Aci(UUID.fromString(memberAci))
            val encryptedUserId = groupSecretParams.encryptServiceId(serviceId)

            val actions = com.vayunmathur.messages.signal.proto.GroupChange.Actions.newBuilder()
                .setVersion(currentGroup.revision + 1)
                .addDeleteMembers(
                    com.vayunmathur.messages.signal.proto.GroupChange.Actions.DeleteMemberAction.newBuilder()
                        .setDeletedUserId(com.google.protobuf.ByteString.copyFrom(encryptedUserId))
                )

            submitGroupChange(groupId, masterKey, auth, actions.build())
        } catch (e: Exception) {
            Log.e(TAG, "kickMember failed", e)
            false
        }
    }

    // Issue #9: Ban a member (AddBannedMemberAction)
    suspend fun banMember(groupId: String, serviceId: String): Boolean {
        return try {
            val stored = groupStore.getGroup(groupId) ?: return false
            val masterKey = stored.masterKey
            val auth = getGroupAuth(masterKey) ?: return false
            val currentGroup = getOrFetchGroup(groupId, masterKey) ?: return false
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)

            val sid = ServiceId.Aci(UUID.fromString(serviceId))
            val encryptedUserId = groupSecretParams.encryptServiceId(sid)

            val bannedMember = com.vayunmathur.messages.signal.proto.MemberBanned.newBuilder()
                .setUserId(com.google.protobuf.ByteString.copyFrom(encryptedUserId))
                .setTimestamp(System.currentTimeMillis())

            val actions = com.vayunmathur.messages.signal.proto.GroupChange.Actions.newBuilder()
                .setVersion(currentGroup.revision + 1)
                .addAddBannedMembers(
                    com.vayunmathur.messages.signal.proto.GroupChange.Actions.AddBannedMemberAction.newBuilder()
                        .setAdded(bannedMember)
                )

            submitGroupChange(groupId, masterKey, auth, actions.build())
        } catch (e: Exception) {
            Log.e(TAG, "banMember failed", e)
            false
        }
    }

    // Issue #9: Leave group (DeleteMemberAction with own ACI)
    suspend fun leaveGroup(groupId: String): Boolean {
        return kickMember(groupId, aci)
    }

    // Issue #9: Unban a member (DeleteBannedMemberAction)
    suspend fun unbanMember(groupId: String, serviceId: String): Boolean {
        return try {
            val stored = groupStore.getGroup(groupId) ?: return false
            val masterKey = stored.masterKey
            val auth = getGroupAuth(masterKey) ?: return false
            val currentGroup = getOrFetchGroup(groupId, masterKey) ?: return false
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)

            val sid = ServiceId.Aci(UUID.fromString(serviceId))
            val encryptedUserId = groupSecretParams.encryptServiceId(sid)

            val actions = com.vayunmathur.messages.signal.proto.GroupChange.Actions.newBuilder()
                .setVersion(currentGroup.revision + 1)
                .addDeleteBannedMembers(
                    com.vayunmathur.messages.signal.proto.GroupChange.Actions.DeleteBannedMemberAction.newBuilder()
                        .setDeletedUserId(com.google.protobuf.ByteString.copyFrom(encryptedUserId))
                )

            submitGroupChange(groupId, masterKey, auth, actions.build())
        } catch (e: Exception) {
            Log.e(TAG, "unbanMember failed", e)
            false
        }
    }

    // Issue #16: Set member role (ModifyMemberRoleAction)
    suspend fun setMemberRole(groupId: String, memberAci: String, role: MemberRole): Boolean {
        return try {
            val stored = groupStore.getGroup(groupId) ?: return false
            val masterKey = stored.masterKey
            val auth = getGroupAuth(masterKey) ?: return false
            val currentGroup = getOrFetchGroup(groupId, masterKey) ?: return false
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)

            val serviceId = ServiceId.Aci(UUID.fromString(memberAci))
            val encryptedUserId = groupSecretParams.encryptServiceId(serviceId)

            val presentation = getGroupAuthPresentation(masterKey) ?: return false

            val actions = com.vayunmathur.messages.signal.proto.GroupChange.Actions.newBuilder()
                .setVersion(currentGroup.revision + 1)
                .addModifyMemberRoles(
                    com.vayunmathur.messages.signal.proto.GroupChange.Actions.ModifyMemberRoleAction.newBuilder()
                        .setUserId(com.google.protobuf.ByteString.copyFrom(encryptedUserId))
                        .setRole(com.vayunmathur.messages.signal.proto.Member.Role.forNumber(role.value))
                )

            submitGroupChange(groupId, masterKey, auth, actions.build())
        } catch (e: Exception) {
            Log.e(TAG, "setMemberRole failed", e)
            false
        }
    }

    private suspend fun submitGroupChange(
        groupId: String,
        masterKey: ByteArray,
        auth: GroupAuthResult,
        actions: com.vayunmathur.messages.signal.proto.GroupChange.Actions,
    ): Boolean {
        val response = SignalHttpClient.request(
            host = SignalHttpClient.STORAGE_HOST,
            method = "PATCH",
            path = "/v2/groups",
            body = actions.toByteArray(),
            contentType = "application/x-protobuf",
            username = auth.username,
            password = auth.password,
        )
        return if (response.code == 200) {
            invalidateCachedGroup(groupId)
            fetchGroup(groupId, masterKey)
            true
        } else {
            Log.e(TAG, "Group change failed with status ${response.code}")
            false
        }
    }

    private suspend fun getGroupAuthPresentation(masterKey: ByteArray): ByteArray? {
        return try {
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)
            val todaySeconds = (System.currentTimeMillis() / 1000 / 86400) * 86400
            val credential = getOrFetchCredential(todaySeconds) ?: return null
            val serverPublicParams = ServerPublicParams(SERVER_PUBLIC_PARAMS)
            val clientZkAuth = ClientZkAuthOperations(serverPublicParams)
            val authCredResponse = AuthCredentialWithPniResponse(credential)
            val authCred = clientZkAuth.receiveAuthCredentialWithPniAsServiceId(
                ServiceId.Aci(UUID.fromString(aci)),
                ServiceId.Pni(UUID.fromString(pni)),
                todaySeconds,
                authCredResponse,
            )
            val presentation = clientZkAuth.createAuthCredentialPresentation(
                java.security.SecureRandom(),
                groupSecretParams,
                authCred,
            )
            presentation.serialize()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get group auth presentation", e)
            null
        }
    }

    enum class AccessControl(val value: Int) {
        UNKNOWN(0), ANY(1), MEMBER(2), ADMINISTRATOR(3), UNSATISFIABLE(4)
    }

    enum class MemberRole(val value: Int) {
        UNKNOWN(0), DEFAULT(1), ADMINISTRATOR(2)
    }

    data class GroupMember(
        val aci: UUID,
        val role: MemberRole = MemberRole.UNKNOWN,
        val profileKey: ByteArray = ByteArray(0),
        val joinedAtRevision: Int = 0,
    )

    data class GroupAccessControl(
        val members: AccessControl,
        val addFromInviteLink: AccessControl,
        val attributes: AccessControl,
    )

    data class PendingMember(
        val serviceId: String,
        val role: MemberRole,
        val addedByUserId: UUID,
        val timestamp: Long,
    )

    data class RequestingMember(
        val aci: UUID,
        val profileKey: ByteArray,
        val timestamp: Long,
    )

    data class BannedMember(
        val serviceId: String,
        val timestamp: Long,
    )

    data class SignalGroup(
        val groupId: String,
        val title: String,
        val members: List<GroupMember>,
        val avatarPath: String?,
        val revision: Int,
        val description: String? = null,
        val disappearingMessagesDuration: Int = 0,
        val announcementsOnly: Boolean = false,
        val accessControl: GroupAccessControl? = null,
        val pendingMembers: List<PendingMember> = emptyList(),
        val requestingMembers: List<RequestingMember> = emptyList(),
        val bannedMembers: List<BannedMember> = emptyList(),
        val inviteLinkPassword: String? = null,
    ) {
        val memberAcis: List<String> get() = members.map { it.aci.toString() }

        fun findMemberOrEmpty(aci: UUID): GroupMember {
            return members.find { it.aci == aci } ?: GroupMember(aci)
        }
    }

    data class GroupChange(
        var groupMasterKey: ByteArray,
        val sourceServiceId: String? = null,
        var revision: Int = 0,
        val addMembers: MutableList<GroupMember> = mutableListOf(),
        val deleteMembers: MutableList<UUID> = mutableListOf(),
        val modifyMemberRoles: MutableList<Pair<UUID, MemberRole>> = mutableListOf(),
        val modifyMemberProfileKeys: MutableList<Pair<UUID, ByteArray>> = mutableListOf(),
        val addPendingMembers: MutableList<PendingMember> = mutableListOf(),
        val deletePendingMembers: MutableList<String> = mutableListOf(),
        val promotePendingMembers: MutableList<Pair<UUID, ByteArray>> = mutableListOf(),
        var modifyTitle: String? = null,
        var modifyAvatar: String? = null,
        var modifyDisappearingMessagesDuration: Int? = null,
        var modifyAttributesAccess: AccessControl? = null,
        var modifyMemberAccess: AccessControl? = null,
        var modifyAddFromInviteLinkAccess: AccessControl? = null,
        val addRequestingMembers: MutableList<RequestingMember> = mutableListOf(),
        val deleteRequestingMembers: MutableList<UUID> = mutableListOf(),
        val promoteRequestingMembers: MutableList<Pair<UUID, MemberRole>> = mutableListOf(),
        var modifyDescription: String? = null,
        var modifyAnnouncementsOnly: Boolean? = null,
        val addBannedMembers: MutableList<BannedMember> = mutableListOf(),
        val deleteBannedMembers: MutableList<String> = mutableListOf(),
        val promotePendingPniAciMembers: MutableList<Triple<UUID, ByteArray, UUID>> = mutableListOf(),
        var modifyInviteLinkPassword: String? = null,
    ) {
        fun isEmpty(): Boolean {
            return addMembers.isEmpty() && deleteMembers.isEmpty() &&
                modifyMemberRoles.isEmpty() && modifyMemberProfileKeys.isEmpty() &&
                addPendingMembers.isEmpty() &&
                promotePendingMembers.isEmpty() && modifyTitle == null &&
                modifyAvatar == null && modifyDisappearingMessagesDuration == null &&
                modifyAttributesAccess == null && modifyMemberAccess == null &&
                modifyAddFromInviteLinkAccess == null && addRequestingMembers.isEmpty() &&
                deleteRequestingMembers.isEmpty() && promoteRequestingMembers.isEmpty() &&
                modifyDescription == null && modifyAnnouncementsOnly == null &&
                addBannedMembers.isEmpty()
        }

        fun resolveConflict(group: SignalGroup) {
            if (modifyTitle != null && modifyTitle == group.title) modifyTitle = null
            if (modifyDescription != null && modifyDescription == group.description) modifyDescription = null
            if (modifyAvatar != null && modifyAvatar == group.avatarPath) modifyAvatar = null
            if (modifyDisappearingMessagesDuration != null &&
                modifyDisappearingMessagesDuration == group.disappearingMessagesDuration
            ) modifyDisappearingMessagesDuration = null
            if (modifyAttributesAccess != null && modifyAttributesAccess == group.accessControl?.attributes) {
                modifyAttributesAccess = null
            }
            if (modifyMemberAccess != null && modifyMemberAccess == group.accessControl?.members) {
                modifyAttributesAccess = null
            }
            if (modifyAddFromInviteLinkAccess != null &&
                modifyAddFromInviteLinkAccess == group.accessControl?.addFromInviteLink
            ) modifyAddFromInviteLinkAccess = null
            if (modifyAnnouncementsOnly != null && modifyAnnouncementsOnly == group.announcementsOnly) {
                modifyAnnouncementsOnly = null
            }
            val memberMap = group.members.associate { it.aci to it.role }
            val pendingSet = group.pendingMembers.map { it.serviceId }.toSet()
            val requestingSet = group.requestingMembers.map { it.aci }.toSet()
            addMembers.removeAll { memberMap.containsKey(it.aci) }
            promotePendingMembers.removeAll { memberMap.containsKey(it.first) }
            promoteRequestingMembers.removeAll { memberMap.containsKey(it.first) }
            addPendingMembers.removeAll { pendingSet.contains(it.serviceId) }
            addRequestingMembers.removeAll { requestingSet.contains(it.aci) }
            deletePendingMembers.removeAll { !pendingSet.contains(it) }
            deleteRequestingMembers.removeAll { !requestingSet.contains(it) }
            deleteMembers.removeAll { !memberMap.containsKey(it) }
            modifyMemberRoles.removeAll { memberMap[it.first] == it.second }
        }
    }

    data class GroupChangeState(
        val groupState: SignalGroup?,
        val groupChange: GroupChange?,
    )

    private val cache = ConcurrentHashMap<String, SignalGroup>()
    private val credentialCache = ConcurrentHashMap<Long, ByteArray>()
    private val activeCalls = ConcurrentHashMap<String, String>()

    fun updateActiveCall(groupId: String, callId: String): Boolean {
        val currentCallId = activeCalls[groupId]
        if (currentCallId != null && currentCallId == callId) {
            activeCalls.remove(groupId)
            return false
        }
        activeCalls[groupId] = callId
        return true
    }

    suspend fun fetchGroup(groupId: String, masterKey: ByteArray): SignalGroup? {
        return try {
            val auth = getGroupAuth(masterKey) ?: return null

            val response = SignalHttpClient.request(
                host = SignalHttpClient.STORAGE_HOST,
                method = "GET",
                path = "/v2/groups",
                contentType = "application/x-protobuf",
                username = auth.username,
                password = auth.password,
            )
            if (response.code != 200) return null

            val groupResponse = GroupResponse.parseFrom(response.body?.bytes())
            val groupProto = groupResponse.group

            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)

            val members = groupProto.membersList.map { member ->
                val decryptedServiceId = groupSecretParams.decryptServiceId(member.userId.toByteArray())
                val decryptedProfileKey = groupSecretParams.decryptProfileKey(member.profileKey.toByteArray(), decryptedServiceId)
                val bb = ByteBuffer.wrap(decryptedServiceId.toByteArray())
                GroupMember(
                    aci = UUID(bb.getLong(), bb.getLong()),
                    role = MemberRole.entries.find { it.value == member.role.number } ?: MemberRole.UNKNOWN,
                    profileKey = decryptedProfileKey.serialize(),
                    joinedAtRevision = member.joinedAtVersion,
                )
            }

            val titleBlob = GroupAttributeBlob.parseFrom(
                groupSecretParams.decryptBlobWithPadding(groupProto.title.toByteArray())
            )
            val decryptedTitle = cleanupStringProperty(titleBlob.title)

            val decryptedDescription = runCatching {
                val descBlob = GroupAttributeBlob.parseFrom(
                    groupSecretParams.decryptBlobWithPadding(groupProto.description.toByteArray())
                )
                cleanupStringProperty(descBlob.description)
            }.getOrNull()

            val disappearingMessagesDuration = if (groupProto.disappearingMessagesTimer != null && !groupProto.disappearingMessagesTimer.isEmpty) {
                val timerBlob = GroupAttributeBlob.parseFrom(
                    groupSecretParams.decryptBlobWithPadding(groupProto.disappearingMessagesTimer.toByteArray())
                )
                timerBlob.disappearingMessagesDuration
            } else 0

            val pendingMembers = groupProto.membersPendingProfileKeyList.mapNotNull { pendingMember ->
                runCatching {
                    val memberProto = pendingMember.member ?: return@runCatching null
                    val decryptedServiceId = groupSecretParams.decryptServiceId(memberProto.userId.toByteArray())
                    val addedByServiceId = groupSecretParams.decryptServiceId(pendingMember.addedByUserId.toByteArray())
                    val addedByBb = ByteBuffer.wrap(addedByServiceId.toByteArray())
                    PendingMember(
                        serviceId = decryptedServiceId.toString(),
                        role = MemberRole.entries.find { it.value == memberProto.role.number } ?: MemberRole.UNKNOWN,
                        addedByUserId = UUID(addedByBb.getLong(), addedByBb.getLong()),
                        timestamp = pendingMember.timestamp,
                    )
                }.getOrNull()
            }

            val requestingMembers = groupProto.membersPendingAdminApprovalList.mapNotNull { reqMember ->
                runCatching {
                    val decryptedServiceId = groupSecretParams.decryptServiceId(reqMember.userId.toByteArray())
                    val decryptedProfileKey = groupSecretParams.decryptProfileKey(reqMember.profileKey.toByteArray(), decryptedServiceId)
                    val bb = ByteBuffer.wrap(decryptedServiceId.toByteArray())
                    RequestingMember(
                        aci = UUID(bb.getLong(), bb.getLong()),
                        profileKey = decryptedProfileKey.serialize(),
                        timestamp = reqMember.timestamp,
                    )
                }.getOrNull()
            }

            val bannedMembers = groupProto.membersBannedList.mapNotNull { banned ->
                runCatching {
                    val decryptedServiceId = groupSecretParams.decryptServiceId(banned.userId.toByteArray())
                    BannedMember(
                        serviceId = decryptedServiceId.toString(),
                        timestamp = banned.timestamp,
                    )
                }.getOrNull()
            }

            val group = SignalGroup(
                groupId = groupId,
                title = decryptedTitle,
                members = members,
                avatarPath = groupProto.avatarUrl.ifEmpty { null },
                revision = groupProto.version,
                description = decryptedDescription,
                disappearingMessagesDuration = disappearingMessagesDuration.toInt(),
                announcementsOnly = groupProto.announcementsOnly,
                accessControl = groupProto.accessControl?.let {
                    GroupAccessControl(
                        members = AccessControl.entries.find { ac -> ac.value == it.members.number } ?: AccessControl.UNKNOWN,
                        addFromInviteLink = AccessControl.entries.find { ac -> ac.value == it.addFromInviteLink.number } ?: AccessControl.UNKNOWN,
                        attributes = AccessControl.entries.find { ac -> ac.value == it.attributes.number } ?: AccessControl.UNKNOWN,
                    )
                },
                pendingMembers = pendingMembers,
                requestingMembers = requestingMembers,
                bannedMembers = bannedMembers,
                inviteLinkPassword = groupProto.inviteLinkPassword?.let {
                    if (it.isEmpty) null else Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP)
                },
            )

            for (member in group.members) {
                recipientStore.storeProfileKey(member.aci.toString(), member.profileKey)
            }
            for (reqMember in group.requestingMembers) {
                recipientStore.storeProfileKey(reqMember.aci.toString(), reqMember.profileKey)
            }

            cache[groupId] = group
            groupStore.storeGroup(
                SignalGroupEntity(
                    groupId = groupId,
                    masterKey = masterKey,
                    title = group.title,
                    avatarUrl = group.avatarPath,
                    revision = group.revision,
                )
            )
            group
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch group $groupId", e)
            null
        }
    }

    suspend fun retrieveGroupByID(groupId: String, masterKey: ByteArray, revision: Int = 0): SignalGroup? {
        val cached = cache[groupId]
        if (cached != null && cached.revision >= revision) {
            return cached
        }
        return fetchGroup(groupId, masterKey)
    }

    suspend fun getOrFetchGroup(groupId: String, masterKey: ByteArray): SignalGroup? {
        cache[groupId]?.let { return it }
        return fetchGroup(groupId, masterKey)
    }

    fun getCachedGroup(groupId: String): SignalGroup? = cache[groupId]

    fun invalidateCachedGroup(groupId: String) {
        cache.remove(groupId)
    }

    fun deriveGroupId(masterKey: ByteArray): String {
        val groupMasterKey = GroupMasterKey(masterKey)
        val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)
        val groupId = groupSecretParams.publicParams.groupIdentifier.serialize()
        return Base64.encodeToString(groupId, Base64.NO_WRAP)
    }

    suspend fun storeMasterKey(groupId: String, masterKey: ByteArray) {
        groupStore.storeGroup(
            SignalGroupEntity(
                groupId = groupId,
                masterKey = masterKey,
                title = "",
                avatarUrl = null,
                revision = 0,
            )
        )
    }

    suspend fun downloadGroupAvatar(avatarPath: String, masterKey: ByteArray): ByteArray? {
        return try {
            val response = SignalHttpClient.request(
                host = SignalHttpClient.CDN1_HOST,
                method = "GET",
                path = avatarPath,
                username = aci,
                password = password,
            )
            if (response.code !in 200..299) return null
            val encryptedAvatar = response.body?.bytes() ?: return null
            decryptGroupAvatar(encryptedAvatar, masterKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download group avatar", e)
            null
        }
    }

    suspend fun uploadGroupAvatar(groupId: String, avatarBytes: ByteArray): Boolean {
        return try {
            val stored = groupStore.getGroup(groupId) ?: return false
            val masterKey = stored.masterKey
            val auth = getGroupAuth(masterKey) ?: return false
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)

            val currentGroup = getOrFetchGroup(groupId, masterKey) ?: return false
            val newRevision = currentGroup.revision + 1

            val avatarBlob = GroupAttributeBlob.newBuilder()
                .setAvatar(com.google.protobuf.ByteString.copyFrom(avatarBytes))
                .build()
            val encryptedAvatar = groupSecretParams.encryptBlobWithPadding(avatarBlob.toByteArray())

            val avatarHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(encryptedAvatar)
            val avatarPath = "groups/" + Base64.encodeToString(avatarHash, Base64.URL_SAFE or Base64.NO_WRAP)

            val uploadResponse = SignalHttpClient.request(
                host = SignalHttpClient.CDN1_HOST,
                method = "PUT",
                path = avatarPath,
                body = encryptedAvatar,
                contentType = "application/octet-stream",
                username = auth.username,
                password = auth.password,
            )
            if (uploadResponse.code !in 200..299) {
                Log.e(TAG, "Avatar upload failed: ${uploadResponse.code}")
                return false
            }

            val actionsBuilder = com.vayunmathur.messages.signal.proto.GroupChange.Actions.newBuilder()
                .setVersion(newRevision)
                .setModifyAvatar(
                    com.vayunmathur.messages.signal.proto.GroupChange.Actions.ModifyAvatarAction.newBuilder()
                        .setAvatar(avatarPath)
                )

            submitGroupChange(groupId, masterKey, auth, actionsBuilder.build())
        } catch (e: Exception) {
            Log.e(TAG, "uploadGroupAvatar failed for $groupId", e)
            false
        }
    }

    suspend fun setGroupDescription(groupId: String, newDescription: String): Boolean {
        return try {
            val stored = groupStore.getGroup(groupId) ?: return false
            val masterKey = stored.masterKey
            val auth = getGroupAuth(masterKey) ?: return false
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)

            val currentGroup = getOrFetchGroup(groupId, masterKey) ?: return false
            val newRevision = currentGroup.revision + 1

            val descBlob = GroupAttributeBlob.newBuilder().setDescription(newDescription).build()
            val encryptedDesc = groupSecretParams.encryptBlobWithPadding(descBlob.toByteArray())

            val actionsBuilder = com.vayunmathur.messages.signal.proto.GroupChange.Actions.newBuilder()
                .setVersion(newRevision)
                .setModifyDescription(
                    com.vayunmathur.messages.signal.proto.GroupChange.Actions.ModifyDescriptionAction.newBuilder()
                        .setDescription(com.google.protobuf.ByteString.copyFrom(encryptedDesc))
                )

            val response = SignalHttpClient.request(
                host = SignalHttpClient.STORAGE_HOST,
                method = "PATCH",
                path = "/v2/groups",
                body = actionsBuilder.build().toByteArray(),
                contentType = "application/x-protobuf",
                username = auth.username,
                password = auth.password,
            )

            if (response.code == 200) {
                invalidateCachedGroup(groupId)
                fetchGroup(groupId, masterKey)
                true
            } else {
                Log.e(TAG, "setGroupDescription failed with status ${response.code}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "setGroupDescription failed for $groupId", e)
            false
        }
    }

    private fun decryptGroupAvatar(encryptedAvatar: ByteArray, masterKey: ByteArray): ByteArray {
        val groupMasterKey = GroupMasterKey(masterKey)
        val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)
        val decryptedBlob = groupSecretParams.decryptBlobWithPadding(encryptedAvatar)
        val blob = GroupAttributeBlob.parseFrom(decryptedBlob)
        return blob.avatar.toByteArray()
    }

    private data class GroupAuthResult(val username: String, val password: String)

    private suspend fun getGroupAuth(masterKey: ByteArray): GroupAuthResult? {
        return try {
            val groupMasterKey = GroupMasterKey(masterKey)
            val groupSecretParams = GroupSecretParams.deriveFromMasterKey(groupMasterKey)
            val groupPublicParams = groupSecretParams.publicParams

            val todaySeconds = (System.currentTimeMillis() / 1000 / 86400) * 86400
            val credential = getOrFetchCredential(todaySeconds) ?: return null

            val serverPublicParams = ServerPublicParams(SERVER_PUBLIC_PARAMS)
            val clientZkAuth = ClientZkAuthOperations(serverPublicParams)

            val authCredResponse = AuthCredentialWithPniResponse(credential)
            val authCred = clientZkAuth.receiveAuthCredentialWithPniAsServiceId(
                ServiceId.Aci(java.util.UUID.fromString(aci)),
                ServiceId.Pni(java.util.UUID.fromString(pni)),
                todaySeconds,
                authCredResponse,
            )
            val presentation = clientZkAuth.createAuthCredentialPresentation(
                java.security.SecureRandom(),
                groupSecretParams,
                authCred,
            )

            GroupAuthResult(
                username = hexEncode(groupPublicParams.serialize()),
                password = hexEncode(presentation.serialize()),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get group auth", e)
            null
        }
    }

    private suspend fun getOrFetchCredential(todaySeconds: Long): ByteArray? {
        credentialCache[todaySeconds]?.let { return it }
        return try {
            val sevenDays = todaySeconds + 7 * 86400
            val path = "/v1/certificate/auth/group?redemptionStartSeconds=$todaySeconds&redemptionEndSeconds=$sevenDays&pniAsServiceId=true"
            val response = ws.sendRequest("GET", path)
            if (response.status != 200) return null

            val json = JSONObject(response.body.toStringUtf8())
            val pniFromResponse = json.optString("pni", "")
            if (pniFromResponse.isNotEmpty() && pniFromResponse != pni) {
                Log.e(TAG, "Mismatching PNI in group credentials: $pniFromResponse != $pni")
                return null
            }
            val credentials = json.getJSONArray("credentials")
            for (i in 0 until credentials.length()) {
                val cred = credentials.getJSONObject(i)
                val redemptionTime = cred.getLong("redemptionTime")
                val credBytes = Base64.decode(cred.getString("credential"), Base64.NO_WRAP)
                credentialCache[redemptionTime] = credBytes
            }
            credentialCache[todaySeconds]
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch group credentials", e)
            null
        }
    }

    companion object {
        private const val TAG = "GroupManager"

        private val SERVER_PUBLIC_PARAMS = Base64.decode(
            "AMhf5ywVwITZMsff/eCyudZx9JDmkkkbV6PInzG4p8x3VqVJSFiMvnvlEKWuRob/1eaIetR31IYeAbm0NdOuHH8Qi+Rexi1wLlpzIo1gstHWBfZzy1+qHRV5A4TqPp15YzBPm0WSggW6PbSn+F4lf57VCnHF7p8SvzAA2ZZJPYJURt8X7bbg+H3i+PEjH9DXItNEqs2sNcug37xZQDLm7X36nOoGPs54XsEGzPdEV+itQNGUFEjY6X9Uv+Acuks7NpyGvCoKxGwgKgE5XyJ+nNKlyHHOLb6N1NuHyBrZrgtY/JYJHRooo5CEqYKBqdFnmbTVGEkCvJKxLnjwKWf+fEPoWeQFj5ObDjcKMZf2Jm2Ae69x+ikU5gBXsRmoF94GXTLfN0/vLt98KDPnxwAQL9j5V1jGOY8jQl6MLxEs56cwXN0dqCnImzVH3TZT1cJ8SW1BRX6qIVxEzjsSGx3yxF3suAilPMqGRp4ffyopjMD1JXiKR2RwLKzizUe5e8XyGOy9fplzhw3jVzTRyUZTRSZKkMLWcQ/gv0E4aONNqs4P+NameAZYOD12qRkxosQQP5uux6B2nRyZ7sAV54DgFyLiRcq1FvwKw2EPQdk4HDoePrO/RNUbyNddnM/mMgj4FW65xCoT1LmjrIjsv/Ggdlx46ueczhMgtBunx1/w8k8V+l8LVZ8gAT6wkU5J+DPQalQguMg12Jzug3q4TbdHiGCmD9EunCwOmsLuLJkz6EcSYXtrlDEnAM+hicw7iergYLLlMXpfTdGxJCWJmP4zqUFeTTmsmhsjGBt7NiEB/9pFFEB3pSbf4iiUukw63Eo8Aqnf4iwob6X1QviCWuc8t0LUlT9vALgh/f2DPVOOmR0RW6bgRvc7DSF20V/omg+YBw==",
            Base64.NO_WRAP,
        )

        private fun hexEncode(bytes: ByteArray): String {
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun cleanupStringProperty(property: String): String {
            val cleaned = property.filter { ch ->
                val type = Character.getType(ch)
                type != Character.CONTROL.toInt() &&
                    type != Character.FORMAT.toInt() &&
                    type != Character.SURROGATE.toInt() &&
                    type != Character.UNASSIGNED.toInt() &&
                    type != Character.PRIVATE_USE.toInt() &&
                    type != Character.LINE_SEPARATOR.toInt() &&
                    type != Character.PARAGRAPH_SEPARATOR.toInt()
            }
            return cleaned.trim()
        }
    }
}
