package com.moxietech.family_tree.server.identity

import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.server.db.AccountTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object AccountRepository {

    fun findByEmail(email: String): Account? = transaction {
        AccountTable.selectAll().where { AccountTable.email eq email }.firstOrNull()?.toAccount()
    }

    fun findById(id: Uuid): Account? = transaction {
        AccountTable.selectAll().where { AccountTable.id eq id }.firstOrNull()?.toAccount()
    }

    fun insert(email: String, displayName: String, locale: Locale): Account = transaction {
        val id = Uuid.random()
        AccountTable.insert {
            it[AccountTable.id] = id
            it[AccountTable.email] = email
            it[AccountTable.displayName] = displayName
            it[AccountTable.locale] = locale.name.lowercase()
        }
        findById(id) ?: error("Account $id was just inserted but cannot be read back")
    }

    private fun ResultRow.toAccount() = Account(
        id = AccountId(this[AccountTable.id].toString()),
        email = this[AccountTable.email],
        displayName = this[AccountTable.displayName],
        locale = Locale.valueOf(this[AccountTable.locale].uppercase()),
    )
}
