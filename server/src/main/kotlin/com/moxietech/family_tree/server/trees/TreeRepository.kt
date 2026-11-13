package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.db.MembershipTable
import com.moxietech.family_tree.server.db.TreeTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object TreeRepository {

    fun findById(id: Uuid): Tree? = transaction {
        TreeTable.selectAll().where { TreeTable.id eq id }.firstOrNull()?.toTree()
    }

    /** Trees the account owns, or is otherwise a member of (membership rows are seeded for the owner too). */
    fun listForAccount(accountId: Uuid): List<Tree> = transaction {
        (TreeTable innerJoin MembershipTable)
            .selectAll()
            .where { MembershipTable.accountId eq accountId }
            .map { it.toTree() }
            .distinctBy { it.id }
    }

    fun insert(name: String, ownerAccountId: AccountId): Tree = transaction {
        val id = Uuid.random()
        TreeTable.insert {
            it[TreeTable.id] = id
            it[TreeTable.name] = name
            it[TreeTable.ownerAccountId] = Uuid.parse(ownerAccountId.value)
            it[TreeTable.version] = 1
        }
        findById(id) ?: error("Tree $id was just inserted but cannot be read back")
    }

    private fun ResultRow.toTree() = Tree(
        id = TreeId(this[TreeTable.id].toString()),
        name = this[TreeTable.name],
        ownerAccountId = AccountId(this[TreeTable.ownerAccountId].toString()),
        version = this[TreeTable.version],
    )
}
