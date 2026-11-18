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
import com.moxietech.family_tree.permissions.GrantRules
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
 * FT-010: creates, replaces and removes grants. Since FT-012 the tree's active owner or an
 * active whole-tree admin manages them (the role table: an admin manages grants):
 * [TreeService.getTreeForManager] before the body, then [WriteAccess.beginManagement]
 * first in the transaction, which serializes admins' management writes under
 * [GraphWriteLock] exclusive. The HTTP routes ([grantRoutes]) sit behind the invitation
 * gate. Branch owners don't manage grants (scoped delegation is a follow-up). FT-011
 * invitation redemption creates proposed
 * grants through [GrantRepository] directly, under the same locks. Every write is audited as
 * entity `Grant` in its own transaction.
 *
 * - The member is resolved in the tree (any membership status); another tree's
 *   account, or an account with no membership here, is the generic 404.
 * - Creates and updates take [GraphWriteLock] first (shared for the owner), like every writer that adds
 *   a person reference, so they wait for a person merge and then see a retired anchor
 *   as the usual 404 instead of failing its foreign key.
 * - The anchor is then locked `FOR KEY SHARE` by ID and tree before the insert or
 *   update (the FT-002 citation-writer contract; 404 when there is no such person). A
 *   person delete that locked first makes this wait and find nothing (404); one that
 *   comes later waits for this transaction and fails the anchor foreign key, which
 *   `RecordDeletion` maps to its 409. Neither side leaves a partial write or audit.
 * - Role and direction must be known names (400). Depth is null or positive
 *   ([Validation.validateBranchDepth]), and since FT-012 an ADMIN grant must cover the
 *   whole tree ([GrantRules.validate], 400). What each role allows is in :core
 *   `EditAuthority`; a stored ADMIN grant on a limited branch carries no authority.
 * - Updates and deletes carry the version last read (409 stale, nothing written).
 *   A new version makes the cached scope stale; a delete removes it (V7 cascade).
 */
object GrantService {

    /** FT-012: every grant of the tree, oldest first, for the owner or a whole-tree admin. */
    suspend fun listGrants(treeId: TreeId, callerAccountId: AccountId): List<GrantDto> =
        TreeService.readManagedTree(treeId, callerAccountId) { tree ->
            GrantRepository.listByTree(Uuid.parse(tree.id.value)).map { it.toDto() }
        }

    suspend fun createGrant(treeId: TreeId, callerAccountId: AccountId, request: CreateGrantRequest): GrantDto {
        val tree = TreeService.getTreeForManager(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val accountUuid = parseUuidField("accountId", request.accountId)
        val anchorUuid = parseUuidField("anchorPersonId", request.anchorPersonId)
        val definition = parseDefinition(request.role, anchorUuid, request.direction, request.depth, request.includePartners)

        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                // The owner takes GraphWriteLock shared as before; an admin exclusive (FT-012).
                WriteAccess.beginManagement(treeUuid, callerAccountId, ownerTakesSharedLock = true)
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
        val tree = TreeService.getTreeForManager(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val grantUuid = parseUuidField("grantId", grantId.value)

        return withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                WriteAccess.beginManagement(treeUuid, callerAccountId, ownerTakesSharedLock = true)
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
        val tree = TreeService.getTreeForManager(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val grantUuid = parseUuidField("grantId", grantId.value)

        withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                // The owner takes no graph lock here, as before; an admin exclusive (FT-012).
                WriteAccess.beginManagement(treeUuid, callerAccountId, ownerTakesSharedLock = false)
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
        val branch = Branch(PersonId(anchor.toString()), direction, depth, includePartners)
        // FT-012: ADMIN is a whole-tree role; on a limited branch it would carry nothing.
        GrantRules.validate(role, branch).requireValid()
        return role to branch
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
