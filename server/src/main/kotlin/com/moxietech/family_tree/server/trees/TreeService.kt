package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.errors.NotFoundException
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

    /** Throws NotFoundException if the tree doesn't exist, ForbiddenException if the caller isn't a member. */
    suspend fun getTreeForMember(treeId: TreeId, callerAccountId: AccountId): Tree = withContext(DatabaseDispatcher) {
        val tree = TreeRepository.findById(Uuid.parse(treeId.value))
            ?: throw NotFoundException("No tree with id ${treeId.value}")
        val isMember = TreeRepository.listForAccount(Uuid.parse(callerAccountId.value))
            .any { it.id == tree.id }
        if (!isMember) throw ForbiddenException("Not a member of tree ${treeId.value}")
        tree
    }
}

fun Tree.toDto() = TreeDto(
    id = id.value,
    name = name,
    ownerAccountId = ownerAccountId.value,
    version = version,
)
