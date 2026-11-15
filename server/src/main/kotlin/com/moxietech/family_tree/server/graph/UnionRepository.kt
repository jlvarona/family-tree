package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.GedcomDate
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.Union
import com.moxietech.family_tree.domain.UnionChild
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.domain.UnionType
import com.moxietech.family_tree.server.db.UnionChildTable
import com.moxietech.family_tree.server.db.UnionTable
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object UnionRepository {

    /** Null when [id] doesn't exist or belongs to another tree; callers treat both as not found. */
    fun findById(treeId: Uuid, id: Uuid): Union? = transaction {
        UnionTable.selectAll().where { (UnionTable.id eq id) and (UnionTable.treeId eq treeId) }.firstOrNull()?.toUnion()
    }

    fun listByTree(treeId: Uuid): List<Union> = transaction {
        UnionTable.selectAll().where { UnionTable.treeId eq treeId }.map { it.toUnion() }
    }

    fun childrenOf(treeId: Uuid, unionId: Uuid): List<UnionChild> = transaction {
        UnionChildTable.selectAll().where {
            (UnionChildTable.unionId eq unionId) and (UnionChildTable.treeId eq treeId)
        }.map {
            UnionChild(
                unionId = UnionId(it[UnionChildTable.unionId].toString()),
                childId = PersonId(it[UnionChildTable.childId].toString()),
                relation = ChildRelation.valueOf(it[UnionChildTable.relation]),
            )
        }
    }

    fun insert(
        treeId: Uuid,
        partner1Id: Uuid,
        partner2Id: Uuid?,
        type: UnionType,
        startDate: GedcomDate?,
        endDate: GedcomDate?,
    ): Union = transaction {
        val id = Uuid.random()
        UnionTable.insert {
            it[UnionTable.id] = id
            it[UnionTable.treeId] = treeId
            it[UnionTable.partner1Id] = partner1Id
            it[UnionTable.partner2Id] = partner2Id
            it[UnionTable.type] = type.name
            it[UnionTable.startDateText] = startDate?.displayText
            it[UnionTable.startDateEarliest] = startDate?.earliest?.toJavaLocalDate()
            it[UnionTable.startDateLatest] = startDate?.latest?.toJavaLocalDate()
            it[UnionTable.endDateText] = endDate?.displayText
            it[UnionTable.endDateEarliest] = endDate?.earliest?.toJavaLocalDate()
            it[UnionTable.endDateLatest] = endDate?.latest?.toJavaLocalDate()
            it[UnionTable.version] = 1
        }
        findById(treeId, id) ?: error("Union $id was just inserted but cannot be read back")
    }

    fun addChild(treeId: Uuid, unionId: Uuid, childId: Uuid, relation: ChildRelation) = transaction {
        UnionChildTable.insert {
            it[UnionChildTable.treeId] = treeId
            it[UnionChildTable.unionId] = unionId
            it[UnionChildTable.childId] = childId
            it[UnionChildTable.relation] = relation.name
        }
    }

    private fun ResultRow.toUnion() = Union(
        id = UnionId(this[UnionTable.id].toString()),
        treeId = TreeId(this[UnionTable.treeId].toString()),
        partner1Id = PersonId(this[UnionTable.partner1Id].toString()),
        partner2Id = this[UnionTable.partner2Id]?.let { PersonId(it.toString()) },
        type = UnionType.valueOf(this[UnionTable.type]),
        startDate = this[UnionTable.startDateText]?.let { text ->
            GedcomDate(
                displayText = text,
                earliest = this[UnionTable.startDateEarliest]?.toKotlinLocalDate(),
                latest = this[UnionTable.startDateLatest]?.toKotlinLocalDate(),
            )
        },
        endDate = this[UnionTable.endDateText]?.let { text ->
            GedcomDate(
                displayText = text,
                earliest = this[UnionTable.endDateEarliest]?.toKotlinLocalDate(),
                latest = this[UnionTable.endDateLatest]?.toKotlinLocalDate(),
            )
        },
        version = this[UnionTable.version],
    )
}
