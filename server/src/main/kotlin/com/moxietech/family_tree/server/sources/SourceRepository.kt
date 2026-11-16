package com.moxietech.family_tree.server.sources

import com.moxietech.family_tree.domain.GedcomDate
import com.moxietech.family_tree.domain.Source
import com.moxietech.family_tree.domain.SourceId
import com.moxietech.family_tree.domain.SourceType
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.db.SourceTable
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

object SourceRepository {

    /** Null when [id] doesn't exist or belongs to another tree; callers treat both as not found. */
    fun findById(treeId: Uuid, id: Uuid): Source? = transaction {
        SourceTable.selectAll().where { (SourceTable.id eq id) and (SourceTable.treeId eq treeId) }.firstOrNull()?.toSource()
    }

    fun listByTree(treeId: Uuid): List<Source> = transaction {
        SourceTable.selectAll().where { SourceTable.treeId eq treeId }.map { it.toSource() }
    }

    fun insert(
        treeId: Uuid,
        title: String,
        type: SourceType,
        author: String?,
        publisher: String?,
        repository: String?,
        url: String?,
        date: GedcomDate?,
    ): Source = transaction {
        val id = Uuid.random()
        SourceTable.insert {
            it[SourceTable.id] = id
            it[SourceTable.treeId] = treeId
            it[SourceTable.title] = title
            it[SourceTable.type] = type.name
            it[SourceTable.author] = author
            it[SourceTable.publisher] = publisher
            it[SourceTable.repository] = repository
            it[SourceTable.url] = url
            it[SourceTable.dateText] = date?.displayText
            it[SourceTable.dateEarliest] = date?.earliest?.toJavaLocalDate()
            it[SourceTable.dateLatest] = date?.latest?.toJavaLocalDate()
            it[SourceTable.version] = 1
        }
        findById(treeId, id) ?: error("Source $id was just inserted but cannot be read back")
    }

    /**
     * FT-001: replaces the fields of source [id] in [treeId] only while its stored
     * version is still [expectedVersion], incrementing it in the same statement.
     * Returns null, changing nothing, when no row matched (a concurrent writer
     * committed first).
     */
    fun update(
        treeId: Uuid,
        id: Uuid,
        expectedVersion: Long,
        title: String,
        type: SourceType,
        author: String?,
        publisher: String?,
        repository: String?,
        url: String?,
        date: GedcomDate?,
    ): Source? = transaction {
        val updated = SourceTable.update({
            (SourceTable.id eq id) and (SourceTable.treeId eq treeId) and (SourceTable.version eq expectedVersion)
        }) {
            it[SourceTable.title] = title
            it[SourceTable.type] = type.name
            it[SourceTable.author] = author
            it[SourceTable.publisher] = publisher
            it[SourceTable.repository] = repository
            it[SourceTable.url] = url
            it[SourceTable.dateText] = date?.displayText
            it[SourceTable.dateEarliest] = date?.earliest?.toJavaLocalDate()
            it[SourceTable.dateLatest] = date?.latest?.toJavaLocalDate()
            it[SourceTable.version] = expectedVersion + 1
        }
        if (updated == 0) null else findById(treeId, id) ?: error("Source $id was just updated but cannot be read back")
    }

    /** FT-001: conditional delete of source [id]; see `PersonRepository.delete`. */
    fun delete(treeId: Uuid, id: Uuid, expectedVersion: Long): Int = transaction {
        SourceTable.deleteWhere {
            (SourceTable.id eq id) and (SourceTable.treeId eq treeId) and (SourceTable.version eq expectedVersion)
        }
    }

    private fun ResultRow.toSource() = Source(
        id = SourceId(this[SourceTable.id].toString()),
        treeId = TreeId(this[SourceTable.treeId].toString()),
        title = this[SourceTable.title],
        type = SourceType.valueOf(this[SourceTable.type]),
        author = this[SourceTable.author],
        publisher = this[SourceTable.publisher],
        repository = this[SourceTable.repository],
        url = this[SourceTable.url],
        date = this[SourceTable.dateText]?.let { text ->
            GedcomDate(
                displayText = text,
                earliest = this[SourceTable.dateEarliest]?.toKotlinLocalDate(),
                latest = this[SourceTable.dateLatest]?.toKotlinLocalDate(),
            )
        },
        version = this[SourceTable.version],
    )
}
