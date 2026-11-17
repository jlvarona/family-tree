package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.db.AccountTable
import com.moxietech.family_tree.server.db.MembershipTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

object MembershipRepository {

    fun insert(treeId: Uuid, accountId: Uuid, personId: Uuid?, status: MembershipStatus) = transaction {
        MembershipTable.insert {
            it[MembershipTable.treeId] = treeId
            it[MembershipTable.accountId] = accountId
            it[MembershipTable.personId] = personId
            it[MembershipTable.status] = status.name
            it[MembershipTable.version] = 1
        }
    }

    /** The account's membership row in the tree, whatever its status; null if there is none. */
    fun find(treeId: Uuid, accountId: Uuid): Membership? = transaction {
        MembershipTable.selectAll()
            .where { (MembershipTable.treeId eq treeId) and (MembershipTable.accountId eq accountId) }
            .firstOrNull()
            ?.toMembership()
    }

    /** FT-011: [find], locked `FOR UPDATE` until the transaction ends. */
    fun lockForUpdate(treeId: Uuid, accountId: Uuid): Membership? = transaction {
        MembershipTable.selectAll()
            .where { (MembershipTable.treeId eq treeId) and (MembershipTable.accountId eq accountId) }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()
            ?.toMembership()
    }

    /** FT-011: the membership in [treeId] linked to [personId], if any (at most one, V9). */
    fun findByPerson(treeId: Uuid, personId: Uuid): Membership? = transaction {
        MembershipTable.selectAll()
            .where { (MembershipTable.treeId eq treeId) and (MembershipTable.personId eq personId) }
            .firstOrNull()
            ?.toMembership()
    }

    /** FT-011: every membership of [treeId] with the account's display name, ordered by account. */
    fun listByTree(treeId: Uuid): List<Pair<Membership, String>> = transaction {
        (MembershipTable innerJoin AccountTable)
            .selectAll()
            .where { MembershipTable.treeId eq treeId }
            .orderBy(MembershipTable.accountId to SortOrder.ASC)
            .map { it.toMembership() to it[AccountTable.displayName] }
    }

    /** FT-011: the display name of a member's account. */
    fun displayName(accountId: Uuid): String = transaction {
        AccountTable.selectAll().where { AccountTable.id eq accountId }.single()[AccountTable.displayName]
    }

    /**
     * FT-011: sets the person and status of [accountId]'s membership in [treeId] while its
     * version is still [expectedVersion], advancing it. Null, changing nothing, when no
     * row matched.
     */
    fun update(
        treeId: Uuid,
        accountId: Uuid,
        expectedVersion: Long,
        personId: Uuid?,
        status: MembershipStatus,
    ): Membership? = transaction {
        val updated = MembershipTable.update({
            (MembershipTable.treeId eq treeId) and (MembershipTable.accountId eq accountId) and
                (MembershipTable.version eq expectedVersion)
        }) {
            it[MembershipTable.personId] = personId
            it[MembershipTable.status] = status.name
            it[MembershipTable.version] = expectedVersion + 1
        }
        if (updated == 0) null else find(treeId, accountId) ?: error("Membership $treeId:$accountId was just updated but cannot be read back")
    }

    /**
     * FT-007: the memberships in [treeId] linked to any of [personIds], whatever their
     * status, ordered by account and locked `FOR UPDATE`.
     */
    fun lockByPeople(treeId: Uuid, personIds: List<Uuid>): List<Membership> = transaction {
        MembershipTable.selectAll()
            .where { (MembershipTable.treeId eq treeId) and (MembershipTable.personId inList personIds) }
            .orderBy(MembershipTable.accountId to SortOrder.ASC)
            .forUpdate(ForUpdateOption.ForUpdate)
            .map { it.toMembership() }
    }

    /**
     * FT-007: relinks [accountId]'s membership in [treeId] from [from] to [to]; status is
     * unchanged and the version advances (V9, FT-011). Returns the number of rows changed
     * (0 when it no longer names [from]).
     */
    fun retargetPerson(treeId: Uuid, accountId: Uuid, from: Uuid, to: Uuid, expectedVersion: Long): Int = transaction {
        MembershipTable.update({
            (MembershipTable.treeId eq treeId) and (MembershipTable.accountId eq accountId) and
                (MembershipTable.personId eq from) and (MembershipTable.version eq expectedVersion)
        }) {
            it[MembershipTable.personId] = to
            it[MembershipTable.version] = expectedVersion + 1
        }
    }

    private fun ResultRow.toMembership() = Membership(
        treeId = TreeId(this[MembershipTable.treeId].toString()),
        accountId = AccountId(this[MembershipTable.accountId].toString()),
        personId = this[MembershipTable.personId]?.let { PersonId(it.toString()) },
        status = MembershipStatus.valueOf(this[MembershipTable.status]),
        version = this[MembershipTable.version],
    )
}
