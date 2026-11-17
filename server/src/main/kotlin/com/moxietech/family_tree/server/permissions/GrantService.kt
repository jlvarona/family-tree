package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.api.CreateGrantRequest
import com.moxietech.family_tree.api.GrantDto
import com.moxietech.family_tree.api.UpdateGrantRequest
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.Grant
import com.moxietech.family_tree.domain.GrantId
import com.moxietech.family_tree.domain.GrantRole
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.errors.requireValid
import com.moxietech.family_tree.server.errors.staleVersion
import com.moxietech.family_tree.server.graph.GraphWriteLock
import com.moxietech.family_tree.server.trees.MembershipRepository
import com.moxietech.family_tree.server.trees.TreeService
import com.moxietech.family_tree.validation.Validation
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import kotlin.uuid.Uuid

/**
 * FT-010: creates, replaces and removes grants. Phase 1 policy stays in force
 * (FT-068): only the tree's active owner may write, through
 * [TreeService.getTreeForWriter]. There are no HTTP routes yet; invitations (FT-011)
 * and grant-based authorization (FT-012) add them. Every write is audited as
 * entity `Grant` in its own transaction.
 *
 * - The member is resolved in the tree (any membership status); another tree's
 *   account, or an account with no membership here, is the generic 404.
 * - Creates and updates take [GraphWriteLock] shared first, like every writer that adds
 *   a person reference, so they wait for a person merge and then see a retired anchor
 *   as the usual 404 instead of failing its foreign key.
 * - The anchor is then locked `FOR KEY SHARE` by ID and tree before the insert or
 *   update (the FT-002 citation-writer contract; 404 when there is no such person). A
 *   person delete that locked first makes this wait and find nothing (404); one that
 *   comes later waits for this transaction and fails the anchor foreign key, which
 *   `RecordDeletion` maps to its 409. Neither side leaves a partial write or audit.
 * - Role and direction must be known names (400). The only definition rule is the
 *   :core [Branch] one: depth is null or positive ([Validation.validateBranchDepth]).
 *   Roles carry no authority until FT-012.
 * - Updates and deletes carry the version last read (409 stale, nothing written).
 *   A new version makes the cached scope stale; a delete removes it (V7 cascade).
 */
object GrantService {

    suspend fun createGrant(treeId: TreeId, callerAccountId: AccountId, request: CreateGrantRequest): GrantDto {
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val accountUuid = parseUuidField("accountId", request.accountId)
        val anchorUuid = parseUuidField("anchorPersonId", request.anchorPersonId)
        val definition = parseDefinition(request.role, anchorUuid, request.direction, request.depth, request.includePartners)

        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                GraphWriteLock.shared(treeUuid)
                MembershipRepository.find(treeUuid, accountUuid) ?: throw NotFoundException("No member with account id $accountUuid")
                requireAnchor(treeUuid, anchorUuid)
                val grant = GrantRepository.insert(treeUuid, accountUuid, definition.first, definition.second, Uuid.parse(callerAccountId.value))
                val dto = grant.toDto()
                audit(treeUuid, callerAccountId, grant.id.value, AuditAction.CREATE, null, AuditJson.encodeToString(dto))
                dto
            }
        }
    }

    suspend fun updateGrant(treeId: TreeId, grantId: GrantId, callerAccountId: AccountId, request: UpdateGrantRequest): GrantDto {
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val grantUuid = parseUuidField("grantId", grantId.value)

        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                GraphWriteLock.shared(treeUuid)
                // The grant first: a missing grant stays a 404 whatever the body says.
                val before = GrantRepository.findById(treeUuid, grantUuid) ?: throw NotFoundException("No grant with id $grantUuid")
                val anchorUuid = parseUuidField("anchorPersonId", request.anchorPersonId)
                val (role, branch) = parseDefinition(request.role, anchorUuid, request.direction, request.depth, request.includePartners)
                requireAnchor(treeUuid, anchorUuid)
                if (before.version != request.version) throw staleVersion("Grant", before.id.value)
                val after = GrantRepository.update(treeUuid, grantUuid, request.version, role, branch)
                    ?: throw staleVersion("Grant", before.id.value)
                val dto = after.toDto()
                audit(treeUuid, callerAccountId, after.id.value, AuditAction.UPDATE, AuditJson.encodeToString(before.toDto()), AuditJson.encodeToString(dto))
                dto
            }
        }
    }

    suspend fun deleteGrant(treeId: TreeId, grantId: GrantId, callerAccountId: AccountId, version: Long) {
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val grantUuid = parseUuidField("grantId", grantId.value)

        withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val before = GrantRepository.findById(treeUuid, grantUuid) ?: throw NotFoundException("No grant with id $grantUuid")
                if (before.version != version) throw staleVersion("Grant", before.id.value)
                if (GrantRepository.delete(treeUuid, grantUuid, version) == 0) throw staleVersion("Grant", before.id.value)
                audit(treeUuid, callerAccountId, before.id.value, AuditAction.DELETE, AuditJson.encodeToString(before.toDto()), null)
            }
        }
    }

    private fun requireAnchor(treeId: Uuid, personId: Uuid) {
        if (!GrantRepository.lockAnchor(treeId, personId)) throw NotFoundException("No person with id $personId")
    }

    private fun parseDefinition(
        rawRole: String,
        anchor: Uuid,
        rawDirection: String,
        depth: Int?,
        includePartners: Boolean,
    ): Pair<GrantRole, Branch> {
        val role = GrantRole.entries.firstOrNull { it.name == rawRole }
            ?: throw ValidationException(listOf("Unknown grant role '$rawRole'"))
        val direction = BranchDirection.entries.firstOrNull { it.name == rawDirection }
            ?: throw ValidationException(listOf("Unknown branch direction '$rawDirection'"))
        Validation.validateBranchDepth(depth).requireValid()
        return role to Branch(PersonId(anchor.toString()), direction, depth, includePartners)
    }

    private fun audit(treeId: Uuid, actor: AccountId, grantId: String, action: AuditAction, before: String?, after: String?) =
        AuditLogRepository.append(
            treeId = treeId,
            actorAccountId = Uuid.parse(actor.value),
            entityType = "Grant",
            entityId = grantId,
            action = action,
            beforeJson = before,
            afterJson = after,
        )
}

fun Grant.toDto() = GrantDto(
    id = id.value,
    treeId = treeId.value,
    accountId = accountId.value,
    role = role.name,
    anchorPersonId = branch.anchorPersonId.value,
    direction = branch.direction.name,
    depth = branch.depth,
    includePartners = branch.includePartners,
    version = version,
)
