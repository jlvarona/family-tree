package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
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
        }
    }

    /** The account's membership row in the tree, whatever its status; null if there is none. */
    fun find(treeId: Uuid, accountId: Uuid): Membership? = transaction {
        MembershipTable.selectAll()
            .where { (MembershipTable.treeId eq treeId) and (MembershipTable.accountId eq accountId) }
            .firstOrNull()
            ?.toMembership()
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
     * unchanged. Returns the number of rows changed (0 when it no longer names [from]).
     * Membership has no version column.
     */
    fun retargetPerson(treeId: Uuid, accountId: Uuid, from: Uuid, to: Uuid): Int = transaction {
        MembershipTable.update({
            (MembershipTable.treeId eq treeId) and (MembershipTable.accountId eq accountId) and (MembershipTable.personId eq from)
        }) {
            it[MembershipTable.personId] = to
        }
    }

    private fun ResultRow.toMembership() = Membership(
        treeId = TreeId(this[MembershipTable.treeId].toString()),
        accountId = AccountId(this[MembershipTable.accountId].toString()),
        personId = this[MembershipTable.personId]?.let { PersonId(it.toString()) },
        status = MembershipStatus.valueOf(this[MembershipTable.status]),
    )
}
