package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.permissions.TreePermissions
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.errors.requireValid
import com.moxietech.family_tree.validation.Validation
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object TreeService {

    suspend fun createTree(ownerAccountId: AccountId, name: String): Tree = withContext(DatabaseDispatcher) {
        Validation.validateTreeName(name).requireValid()
        transaction {
            val tree = TreeRepository.insert(name, ownerAccountId)
            val ownerUuid = Uuid.parse(ownerAccountId.value)
            MembershipRepository.insert(
                treeId = Uuid.parse(tree.id.value),
                accountId = ownerUuid,
                personId = null,
                status = MembershipStatus.ACTIVE,
            )
            AuditLogRepository.append(
                treeId = Uuid.parse(tree.id.value),
                actorAccountId = ownerUuid,
                entityType = "Tree",
                entityId = tree.id.value,
                action = AuditAction.CREATE,
                beforeJson = null,
                afterJson = AuditJson.encodeToString(tree.toDto()),
            )
            tree
        }
    }

    suspend fun listTreesForAccount(accountId: AccountId): List<Tree> = withContext(DatabaseDispatcher) {
        TreeRepository.listForAccount(Uuid.parse(accountId.value))
    }

    /**
     * Read access to [treeId] ([TreePermissions.canRead]): the caller needs an
     * ACTIVE membership. Throws NotFoundException if the tree doesn't exist,
     * ForbiddenException if the caller has no membership or only an invitation.
     */
    suspend fun getTreeForReader(treeId: TreeId, callerAccountId: AccountId): Tree = withContext(DatabaseDispatcher) {
        transaction {
            val (tree, membership) = loadTreeAndMembership(treeId, callerAccountId)
            if (!TreePermissions.canRead(tree, callerAccountId, membership)) {
                throw ForbiddenException("Not a member of tree ${treeId.value}")
            }
            tree
        }
    }

    /**
     * Write access to an existing tree ([TreePermissions.canWrite]): during
     * Phase 1 only the tree's active owner may change it. Every mutation under
     * a tree calls this before validating fields, and its POST route calls it
     * (via `requireTreeWriter`) before decoding the body, so a non-owner gets
     * 403 whatever the body, malformed JSON included.
     * FT-012 (grants) and FT-014 (proposals) replace it.
     */
    suspend fun getTreeForWriter(treeId: TreeId, callerAccountId: AccountId): Tree = withContext(DatabaseDispatcher) {
        transaction {
            val (tree, membership) = loadTreeAndMembership(treeId, callerAccountId)
            if (!TreePermissions.canRead(tree, callerAccountId, membership)) {
                throw ForbiddenException("Not a member of tree ${treeId.value}")
            }
            if (!TreePermissions.canWrite(tree, callerAccountId, membership)) {
                throw ForbiddenException("Only the tree owner can make changes to tree ${treeId.value}")
            }
            tree
        }
    }

    private fun loadTreeAndMembership(treeId: TreeId, callerAccountId: AccountId): Pair<Tree, Membership?> {
        // First conversion of a caller-supplied tree ID: malformed is a 400 (FT-070).
        val treeUuid = parseUuidField("treeId", treeId.value)
        val tree = TreeRepository.findById(treeUuid)
            ?: throw NotFoundException("No tree with id ${treeId.value}")
        return tree to MembershipRepository.find(treeUuid, Uuid.parse(callerAccountId.value))
    }
}

fun Tree.toDto() = TreeDto(
    id = id.value,
    name = name,
    ownerAccountId = ownerAccountId.value,
    version = version,
)
