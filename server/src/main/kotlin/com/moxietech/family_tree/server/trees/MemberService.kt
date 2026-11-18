package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.api.LinkMemberPersonRequest
import com.moxietech.family_tree.api.MemberDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.parseOptionalUuidField
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.errors.staleVersion
import com.moxietech.family_tree.server.graph.GraphWriteLock
import com.moxietech.family_tree.server.graph.isUniqueViolation
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.SQLException
import kotlin.uuid.Uuid

/**
 * FT-011: the tree owner's view of members and account-to-person linking, behind the
 * same gate as invitations ([InvitationSettings.enabled]; routes not installed and `404`
 * while off) and the Phase 1 owner policy ([TreeService.getTreeForWriter]).
 *
 * Linking (`PUT …/members/{accountId}/person`) sets or clears the person a membership
 * is linked to, at the membership's version (`409` stale), in one READ COMMITTED
 * transaction: [GraphWriteLock] shared (a person reference, so a merge waits for it),
 * the membership `FOR UPDATE` (`404` when the account has no membership in this tree),
 * the person's linking reservation in this tree ([InvitationRepository.reservePerson],
 * `404`), then `409` when the person is linked to another member or promised to a
 * pending invitation. The reservation serializes this with invitation create and
 * redemption for the same person; `membership_tree_person_key` is the backstop (`409`). The change is
 * audited as `Membership`. A member's home branch follows the link at read time
 * ([com.moxietech.family_tree.server.permissions.HomeBranchService]); no grant or cache
 * changes.
 */
class MemberService(private val settings: InvitationSettings = InvitationSettings()) {

    suspend fun list(treeId: TreeId, callerAccountId: AccountId): List<MemberDto> {
        requireEnabled()
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        return withContext(DatabaseDispatcher) {
            MembershipRepository.listByTree(Uuid.parse(tree.id.value)).map { (membership, name) -> membership.toMemberDto(name) }
        }
    }

    suspend fun linkPerson(treeId: TreeId, accountId: String, callerAccountId: AccountId, request: LinkMemberPersonRequest): MemberDto {
        requireEnabled()
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val accountUuid = parseUuidField("accountId", accountId)
        val personUuid = parseOptionalUuidField("personId", request.personId)
        return try {
            withContext(DatabaseDispatcher) {
                transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                    GraphWriteLock.shared(treeUuid)
                    val before = MembershipRepository.lockForUpdate(treeUuid, accountUuid)
                        ?: throw NotFoundException("No member with account id $accountUuid")
                    if (before.version != request.version) throw staleVersion("Membership", before.auditEntityId())
                    if (personUuid != null) {
                        // The linking reservation, shared with invitation create and redemption.
                        if (!InvitationRepository.reservePerson(treeUuid, personUuid)) throw NotFoundException("No person with id $personUuid")
                        val linked = MembershipRepository.findByPerson(treeUuid, personUuid)
                        if (linked != null && linked.accountId != before.accountId) throw ConflictException(INVITATION_PERSON_LINKED_MESSAGE)
                        if (InvitationRepository.findForPerson(treeUuid, personUuid) != null) throw ConflictException(INVITATION_PERSON_PENDING_MESSAGE)
                    }
                    val after = MembershipRepository.update(treeUuid, accountUuid, request.version, personUuid, before.status)
                        ?: throw staleVersion("Membership", before.auditEntityId())
                    AuditLogRepository.append(
                        treeId = treeUuid,
                        actorAccountId = Uuid.parse(callerAccountId.value),
                        entityType = "Membership",
                        entityId = after.auditEntityId(),
                        action = AuditAction.UPDATE,
                        beforeJson = AuditJson.encodeToString(before.auditSnapshot()),
                        afterJson = AuditJson.encodeToString(after.auditSnapshot()),
                    )
                    after.toMemberDto(MembershipRepository.displayName(accountUuid))
                }
            }
        } catch (e: SQLException) {
            if (e.isUniqueViolation("membership_tree_person_key")) throw ConflictException(INVITATION_PERSON_LINKED_MESSAGE)
            throw e
        }
    }

    private fun requireEnabled() {
        if (!settings.enabled) throw NotFoundException(InvitationService.NOT_FOUND_MESSAGE)
    }
}

internal fun Membership.toMemberDto(displayName: String) = MemberDto(
    treeId = treeId.value,
    accountId = accountId.value,
    displayName = displayName,
    personId = personId?.value,
    status = status.name,
    version = version,
)
