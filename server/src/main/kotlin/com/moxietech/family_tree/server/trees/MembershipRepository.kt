package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.db.MembershipTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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

    private fun ResultRow.toMembership() = Membership(
        treeId = TreeId(this[MembershipTable.treeId].toString()),
        accountId = AccountId(this[MembershipTable.accountId].toString()),
        personId = this[MembershipTable.personId]?.let { PersonId(it.toString()) },
        status = MembershipStatus.valueOf(this[MembershipTable.status]),
    )
}
