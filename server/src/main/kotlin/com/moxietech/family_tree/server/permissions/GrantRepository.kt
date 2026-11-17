package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.Grant
import com.moxietech.family_tree.domain.GrantId
import com.moxietech.family_tree.domain.GrantRole
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.db.TreeGrantTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.uuid.Uuid

/** FT-010: `tree_grant` rows (V7), always filtered by tree. */
object GrantRepository {

    /** Null when [id] doesn't exist or belongs to another tree; callers treat both as not found. */
    fun findById(treeId: Uuid, id: Uuid): Grant? = transaction {
        TreeGrantTable.selectAll().where { (TreeGrantTable.id eq id) and (TreeGrantTable.treeId eq treeId) }
            .firstOrNull()?.toGrant()
    }

    /** Every grant [accountId] holds in [treeId], oldest first. */
    fun listForAccount(treeId: Uuid, accountId: Uuid): List<Grant> = transaction {
        TreeGrantTable.selectAll()
            .where { (TreeGrantTable.treeId eq treeId) and (TreeGrantTable.accountId eq accountId) }
            .orderBy(TreeGrantTable.createdAt to SortOrder.ASC, TreeGrantTable.id to SortOrder.ASC)
            .map { it.toGrant() }
    }

    /**
     * FT-007: the IDs of the grants in [treeId] anchored at [personId], in ID order,
     * locked `FOR SHARE` so none can be re-anchored or removed until the merge's
     * transaction ends.
     */
    fun lockAnchoredAt(treeId: Uuid, personId: Uuid): List<Uuid> = withJdbc { connection ->
        connection.prepareStatement(
            "select id from tree_grant where tree_id = ? and anchor_person_id = ? order by id for share",
        ).use { st ->
            st.setUuid(1, treeId)
            st.setUuid(2, personId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(Uuid.parse(rs.getString(1))) } }
        }
    }

    /**
     * Scope rebuilds: the grant, locked `FOR NO KEY UPDATE` so concurrent rebuilds of
     * one grant, and a rebuild and a grant update or delete, take turns. Null when it
     * no longer exists in [treeId].
     */
    fun lockForScopeRebuild(treeId: Uuid, id: Uuid): Grant? {
        val locked = withJdbc { connection ->
            connection.prepareStatement("select 1 from tree_grant where id = ? and tree_id = ? for no key update").use { st ->
                st.setUuid(1, id)
                st.setUuid(2, treeId)
                st.executeQuery().use { it.next() }
            }
        }
        return if (locked) findById(treeId, id) else null
    }

    /**
     * Grant writers: locks the anchor person `FOR KEY SHARE` by ID and tree (the
     * FT-002 citation-writer contract), false when there is no such person. A delete
     * that locked the person first makes this wait and then find nothing (404); one
     * that comes after waits for this transaction and fails the anchor foreign key,
     * which [com.moxietech.family_tree.server.graph.RecordDeletion] maps to its 409.
     */
    fun lockAnchor(treeId: Uuid, personId: Uuid): Boolean = withJdbc { connection ->
        connection.prepareStatement("select 1 from person where id = ? and tree_id = ? for key share").use { st ->
            st.setUuid(1, personId)
            st.setUuid(2, treeId)
            st.executeQuery().use { it.next() }
        }
    }

    fun insert(treeId: Uuid, accountId: Uuid, role: GrantRole, branch: Branch, createdByAccountId: Uuid): Grant = transaction {
        val id = Uuid.random()
        TreeGrantTable.insert {
            it[TreeGrantTable.id] = id
            it[TreeGrantTable.treeId] = treeId
            it[TreeGrantTable.accountId] = accountId
            it[TreeGrantTable.role] = role.name
            it[TreeGrantTable.anchorPersonId] = Uuid.parse(branch.anchorPersonId.value)
            it[TreeGrantTable.direction] = branch.direction.name
            it[TreeGrantTable.depth] = branch.depth
            it[TreeGrantTable.includePartners] = branch.includePartners
            it[TreeGrantTable.version] = 1
            it[TreeGrantTable.createdByAccountId] = createdByAccountId
            it[TreeGrantTable.createdAt] = Instant.now()
        }
        findById(treeId, id) ?: error("Grant $id was just inserted but cannot be read back")
    }

    /**
     * Replaces role and branch of [id] in [treeId] while its version is still
     * [expectedVersion], advancing it (which also makes its cached scope stale).
     * Null, changing nothing, when no row matched.
     */
    fun update(treeId: Uuid, id: Uuid, expectedVersion: Long, role: GrantRole, branch: Branch): Grant? = transaction {
        val updated = TreeGrantTable.update({
            (TreeGrantTable.id eq id) and (TreeGrantTable.treeId eq treeId) and (TreeGrantTable.version eq expectedVersion)
        }) {
            it[TreeGrantTable.role] = role.name
            it[TreeGrantTable.anchorPersonId] = Uuid.parse(branch.anchorPersonId.value)
            it[TreeGrantTable.direction] = branch.direction.name
            it[TreeGrantTable.depth] = branch.depth
            it[TreeGrantTable.includePartners] = branch.includePartners
            it[TreeGrantTable.version] = expectedVersion + 1
        }
        if (updated == 0) null else findById(treeId, id) ?: error("Grant $id was just updated but cannot be read back")
    }

    /** Deletes [id] in [treeId] at [expectedVersion]; its cache rows go with it (V7 cascade). Rows deleted. */
    fun delete(treeId: Uuid, id: Uuid, expectedVersion: Long): Int = transaction {
        TreeGrantTable.deleteWhere {
            (TreeGrantTable.id eq id) and (TreeGrantTable.treeId eq treeId) and (TreeGrantTable.version eq expectedVersion)
        }
    }

    private fun ResultRow.toGrant() = Grant(
        id = GrantId(this[TreeGrantTable.id].toString()),
        treeId = TreeId(this[TreeGrantTable.treeId].toString()),
        accountId = AccountId(this[TreeGrantTable.accountId].toString()),
        role = GrantRole.valueOf(this[TreeGrantTable.role]),
        branch = Branch(
            anchorPersonId = PersonId(this[TreeGrantTable.anchorPersonId].toString()),
            direction = BranchDirection.valueOf(this[TreeGrantTable.direction]),
            depth = this[TreeGrantTable.depth],
            includePartners = this[TreeGrantTable.includePartners],
        ),
        version = this[TreeGrantTable.version],
    )
}
