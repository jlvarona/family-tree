package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.GrantRole
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.server.db.InvitationGrantTable
import com.moxietech.family_tree.server.db.InvitationTable
import com.moxietech.family_tree.server.permissions.setUuid
import com.moxietech.family_tree.server.permissions.withJdbc
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import kotlin.uuid.Uuid

/** FT-011: a grant proposed by an invitation. */
internal data class ProposedGrant(val role: GrantRole, val branch: Branch)

/** FT-011: a pending invitation row (V9). The token digest is deliberately not part of it. */
internal data class PendingInvitation(
    val id: Uuid,
    val treeId: Uuid,
    val email: String?,
    val personId: Uuid?,
    val expiresAt: Instant,
    val createdByAccountId: Uuid,
    val createdAt: Instant,
    val version: Long,
    val grants: List<ProposedGrant>,
) {
    fun isExpired(now: Instant) = !expiresAt.isAfter(now)
}

/**
 * FT-011: `invitation` and `invitation_grant` rows (V9). Every lookup but the token's is
 * filtered by tree; the token lookup is by digest only, because the redeemer doesn't
 * know the tree, and everything after it is filtered by the tree that row names.
 */
internal object InvitationRepository {

    fun insert(
        treeId: Uuid,
        tokenDigest: ByteArray,
        email: String?,
        personId: Uuid?,
        grants: List<ProposedGrant>,
        createdAt: Instant,
        expiresAt: Instant,
        createdByAccountId: Uuid,
    ): PendingInvitation = transaction {
        val id = Uuid.random()
        InvitationTable.insert {
            it[InvitationTable.id] = id
            it[InvitationTable.treeId] = treeId
            it[InvitationTable.tokenDigest] = tokenDigest
            it[InvitationTable.email] = email
            it[InvitationTable.personId] = personId
            it[InvitationTable.expiresAt] = expiresAt
            it[InvitationTable.createdByAccountId] = createdByAccountId
            it[InvitationTable.createdAt] = createdAt
            it[InvitationTable.version] = 1
        }
        grants.forEachIndexed { index, grant ->
            InvitationGrantTable.insert {
                it[InvitationGrantTable.id] = Uuid.random()
                it[InvitationGrantTable.invitationId] = id
                it[InvitationGrantTable.treeId] = treeId
                it[InvitationGrantTable.ordinal] = index
                it[InvitationGrantTable.role] = grant.role.name
                it[InvitationGrantTable.anchorPersonId] = Uuid.parse(grant.branch.anchorPersonId.value)
                it[InvitationGrantTable.direction] = grant.branch.direction.name
                it[InvitationGrantTable.depth] = grant.branch.depth
                it[InvitationGrantTable.includePartners] = grant.branch.includePartners
            }
        }
        findById(treeId, id) ?: error("Invitation $id was just inserted but cannot be read back")
    }

    /** Null when [id] doesn't exist or belongs to another tree; callers treat both as not found. */
    fun findById(treeId: Uuid, id: Uuid): PendingInvitation? = transaction {
        InvitationTable.selectAll().where { (InvitationTable.id eq id) and (InvitationTable.treeId eq treeId) }
            .firstOrNull()?.let { withGrants(listOf(it)).single() }
    }

    /** [findById], locked `FOR UPDATE` (revocation). */
    fun lockForUpdate(treeId: Uuid, id: Uuid): PendingInvitation? = transaction {
        InvitationTable.selectAll().where { (InvitationTable.id eq id) and (InvitationTable.treeId eq treeId) }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()?.let { withGrants(listOf(it)).single() }
    }

    /** Every pending invitation of [treeId], newest first. */
    fun listByTree(treeId: Uuid): List<PendingInvitation> = transaction {
        withGrants(
            InvitationTable.selectAll().where { InvitationTable.treeId eq treeId }
                .orderBy(InvitationTable.createdAt to SortOrder.DESC, InvitationTable.id to SortOrder.ASC)
                .toList(),
        )
    }

    /**
     * Redemption: the invitation whose token has [digest] **in [treeId]**, found and locked
     * `FOR UPDATE` by one statement filtered by both, so concurrent redemptions and a
     * revocation take turns and only the first finds it. A token presented for another
     * tree matches nothing and locks nothing: it is the same miss as a random token and
     * leaves the invitation usable in its own tree.
     */
    fun lockByDigest(treeId: Uuid, digest: ByteArray): PendingInvitation? = transaction {
        InvitationTable.selectAll().where { (InvitationTable.tokenDigest eq digest) and (InvitationTable.treeId eq treeId) }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()?.let { withGrants(listOf(it)).single() }
    }

    /**
     * The linking reservation on a person: `FOR NO KEY UPDATE` on the person row in
     * [treeId], false when there is no such person. Every writer that links a person to
     * an account or promises it to one takes it before checking for an existing link or
     * pending invitation: invitation create, redemption and owner linking. Those writes
     * land in two tables (`invitation`, `membership`) with no unique index across both,
     * so this row lock is what serializes them; the second writer re-checks after the
     * first commits and gets a `409`. It doesn't block `FOR KEY SHARE` (citation, grant
     * and event writers), and every caller takes it after `GraphWriteLock` and after any
     * invitation or membership row it locks, never before them.
     */
    fun reservePerson(treeId: Uuid, personId: Uuid): Boolean = withJdbc { connection ->
        connection.prepareStatement("select 1 from person where id = ? and tree_id = ? for no key update").use { st ->
            st.setUuid(1, personId)
            st.setUuid(2, treeId)
            st.executeQuery().use { it.next() }
        }
    }

    /** The pending invitation in [treeId] that links [personId], if any (at most one, V9). */
    fun findForPerson(treeId: Uuid, personId: Uuid): Uuid? = transaction {
        InvitationTable.selectAll().where { (InvitationTable.treeId eq treeId) and (InvitationTable.personId eq personId) }
            .firstOrNull()?.get(InvitationTable.id)
    }

    /**
     * FT-007 merges: the IDs of pending invitations in [treeId] that link [personId] or
     * propose a grant anchored at them, in ID order, locked `FOR SHARE` until the merge
     * ends. Invitation writers take `GraphWriteLock` shared, so none can appear meanwhile.
     */
    fun lockNaming(treeId: Uuid, personId: Uuid): List<Uuid> = withJdbc { connection ->
        connection.prepareStatement(
            """
            select i.id from invitation i
            where i.tree_id = ?
              and (i.person_id = ? or exists (
                  select 1 from invitation_grant g
                  where g.invitation_id = i.id and g.tree_id = i.tree_id and g.anchor_person_id = ?))
            order by i.id
            for share of i
            """.trimIndent(),
        ).use { st ->
            st.setUuid(1, treeId)
            st.setUuid(2, personId)
            st.setUuid(3, personId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(Uuid.parse(rs.getString(1))) } }
        }
    }

    /** Deletes [id] in [treeId] at [expectedVersion], with its proposed grants (V9 cascade). Rows deleted. */
    fun delete(treeId: Uuid, id: Uuid, expectedVersion: Long): Int = transaction {
        InvitationTable.deleteWhere {
            (InvitationTable.id eq id) and (InvitationTable.treeId eq treeId) and (InvitationTable.version eq expectedVersion)
        }
    }

    private fun withGrants(rows: List<ResultRow>): List<PendingInvitation> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[InvitationTable.id] }
        val treeId = rows.first()[InvitationTable.treeId]
        val grants = InvitationGrantTable.selectAll()
            .where { (InvitationGrantTable.treeId eq treeId) and (InvitationGrantTable.invitationId inList ids) }
            .orderBy(InvitationGrantTable.invitationId to SortOrder.ASC, InvitationGrantTable.ordinal to SortOrder.ASC)
            .groupBy({ it[InvitationGrantTable.invitationId] }) { row ->
                ProposedGrant(
                    role = GrantRole.valueOf(row[InvitationGrantTable.role]),
                    branch = Branch(
                        anchorPersonId = PersonId(row[InvitationGrantTable.anchorPersonId].toString()),
                        direction = BranchDirection.valueOf(row[InvitationGrantTable.direction]),
                        depth = row[InvitationGrantTable.depth],
                        includePartners = row[InvitationGrantTable.includePartners],
                    ),
                )
            }
        return rows.map { row ->
            val id = row[InvitationTable.id]
            PendingInvitation(
                id = id,
                treeId = row[InvitationTable.treeId],
                email = row[InvitationTable.email],
                personId = row[InvitationTable.personId],
                expiresAt = row[InvitationTable.expiresAt],
                createdByAccountId = row[InvitationTable.createdByAccountId],
                createdAt = row[InvitationTable.createdAt],
                version = row[InvitationTable.version],
                grants = grants[id].orEmpty(),
            )
        }
    }
}
