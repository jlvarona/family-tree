package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.api.TreeCapabilitiesDto
import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.Grant
import com.moxietech.family_tree.server.permissions.GrantRepository
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
import java.sql.Connection
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
     * FT-013: the read gate of [getTreeForReader] and the read itself in one read-only
     * REPEATABLE READ snapshot, so whatever [read] decides about visibility (grants and
     * their scopes, `ReadAccess`) comes from the same data it returns. Same 404 and 403.
     */
    internal suspend fun <T> readAsMember(treeId: TreeId, callerAccountId: AccountId, read: (Tree) -> T): T =
        withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                val (tree, membership) = loadTreeAndMembership(treeId, callerAccountId)
                if (!TreePermissions.canRead(tree, callerAccountId, membership)) {
                    throw ForbiddenException("Not a member of tree ${treeId.value}")
                }
                read(tree)
            }
        }

    /**
     * Owner-only operations ([TreePermissions.canWrite]): since FT-012 these are the
     * ownership and lifecycle operations (person merges, invitations, member linking,
     * grant writes). Their routes call it (via `requireTreeOwner`) before decoding the
     * body, so anyone else gets 403 whatever the body, malformed JSON included.
     */
    suspend fun getTreeForWriter(treeId: TreeId, callerAccountId: AccountId): Tree = withContext(DatabaseDispatcher) {
        transaction {
            val (tree, membership) = loadTreeAndMembership(treeId, callerAccountId)
            if (!TreePermissions.canRead(tree, callerAccountId, membership)) {
                throw ForbiddenException("Not a member of tree ${treeId.value}")
            }
            if (!TreePermissions.canWrite(tree, callerAccountId, membership)) {
                throw ForbiddenException(ownerOnlyMessage(treeId.value))
            }
            tree
        }
    }

    /**
     * FT-012, the first gate of every person, relationship, event and citation write
     * ([TreePermissions.canEditSomewhere]): an active member who can change something.
     * Routes call it (via `requireTreeWriter`) before decoding the body and services
     * before validating it, so a viewer, a member without grants or an invited account
     * gets 403 whatever they send. Which records they may change is decided inside the
     * write's transaction (`WriteAccess`); this gate takes no locks.
     */
    suspend fun getTreeForEditor(treeId: TreeId, callerAccountId: AccountId): Tree = withContext(DatabaseDispatcher) {
        transaction {
            val (tree, membership) = loadTreeAndMembership(treeId, callerAccountId)
            if (!TreePermissions.canRead(tree, callerAccountId, membership)) {
                throw ForbiddenException("Not a member of tree ${treeId.value}")
            }
            val grants = grantsOf(tree, callerAccountId)
            if (!TreePermissions.canEditSomewhere(tree, callerAccountId, membership, grants)) {
                throw ForbiddenException(noEditAccessMessage(treeId.value))
            }
            tree
        }
    }

    /**
     * FT-012, the first gate of tree-wide records (places, sources, media uploads,
     * [TreePermissions.canWriteTreeWide]): the owner or a whole-tree admin. Repeated
     * under locks inside the write (`WriteAccess.beginTreeWide`).
     */
    suspend fun getTreeForTreeWideWriter(treeId: TreeId, callerAccountId: AccountId): Tree = withContext(DatabaseDispatcher) {
        transaction {
            val (tree, membership) = loadTreeAndMembership(treeId, callerAccountId)
            if (!TreePermissions.canRead(tree, callerAccountId, membership)) {
                throw ForbiddenException("Not a member of tree ${treeId.value}")
            }
            val grants = grantsOf(tree, callerAccountId)
            if (!TreePermissions.canWriteTreeWide(tree, callerAccountId, membership, grants)) {
                throw ForbiddenException(
                    if (TreePermissions.canEditSomewhere(tree, callerAccountId, membership, grants)) {
                        treeWideOnlyMessage(treeId.value)
                    } else {
                        noEditAccessMessage(treeId.value)
                    },
                )
            }
            tree
        }
    }

    /**
     * FT-012, the first gate of access management (invitations, grant writes, the member
     * list): the owner or a whole-tree admin. Repeated under locks inside the write
     * (`WriteAccess.beginManagement`). Member linking and merges stay owner-only.
     */
    suspend fun getTreeForManager(treeId: TreeId, callerAccountId: AccountId): Tree =
        readManagedTree(treeId, callerAccountId) { it }

    /** Authorize and read a management list from the same database snapshot. */
    internal suspend fun <T> readManagedTree(treeId: TreeId, callerAccountId: AccountId, read: (Tree) -> T): T =
        withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                val (tree, membership) = loadTreeAndMembership(treeId, callerAccountId)
                if (!TreePermissions.canRead(tree, callerAccountId, membership)) {
                    throw ForbiddenException("Not a member of tree ${treeId.value}")
                }
                val grants = grantsOf(tree, callerAccountId)
                if (!TreePermissions.canWriteTreeWide(tree, callerAccountId, membership, grants)) {
                    throw ForbiddenException(
                        if (TreePermissions.canEditSomewhere(tree, callerAccountId, membership, grants)) {
                            managerOnlyMessage(treeId.value)
                        } else {
                            noEditAccessMessage(treeId.value)
                        },
                    )
                }
                read(tree)
            }
        }

    /**
     * FT-014: the caller's capabilities in [treeId] for client controls, from the same
     * `:core` rules the writes use, in one read-only snapshot (same 404 and 403 as reads).
     * [sharing] is the sharing gate (`INVITATIONS_ENABLED`): without it nobody reviews.
     */
    suspend fun capabilities(treeId: TreeId, callerAccountId: AccountId, sharing: Boolean): TreeCapabilitiesDto =
        withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                val (tree, membership) = loadTreeAndMembership(treeId, callerAccountId)
                if (!TreePermissions.canRead(tree, callerAccountId, membership)) {
                    throw ForbiddenException("Not a member of tree ${treeId.value}")
                }
                val grants = grantsOf(tree, callerAccountId)
                TreeCapabilitiesDto(
                    treeId = tree.id.value,
                    isOwner = tree.ownerAccountId == callerAccountId,
                    canEdit = TreePermissions.canEditSomewhere(tree, callerAccountId, membership, grants),
                    canWriteTreeWide = TreePermissions.canWriteTreeWide(tree, callerAccountId, membership, grants),
                    canReview = sharing && TreePermissions.canReviewSomewhere(tree, callerAccountId, membership, grants),
                    proposals = sharing,
                )
            }
        }

    /** FT-012: 403 for a member who can edit but not manage access. */
    fun managerOnlyMessage(treeId: String) = "Only the tree owner or an admin can manage access to tree $treeId"

    /** 403 for an owner-only operation. */
    fun ownerOnlyMessage(treeId: String) = "Only the tree owner can make changes to tree $treeId"

    /** FT-012: 403 for an active member whose grants allow no change (viewer, none, or a limited ADMIN). */
    fun noEditAccessMessage(treeId: String) = "Your access to tree $treeId doesn't allow changes"

    /** FT-012: 403 for a member who can edit but not tree-wide records. */
    fun treeWideOnlyMessage(treeId: String) = "Only the tree owner or an admin can change tree-wide records in tree $treeId"

    private fun grantsOf(tree: Tree, callerAccountId: AccountId): List<Grant> =
        if (tree.ownerAccountId == callerAccountId) {
            emptyList()
        } else {
            GrantRepository.listForAccount(Uuid.parse(tree.id.value), Uuid.parse(callerAccountId.value))
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
