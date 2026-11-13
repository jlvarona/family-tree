package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.server.db.MembershipTable
import org.jetbrains.exposed.v1.jdbc.insert
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
}
