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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
        }.map { it.toUnionChild() }
    }

    /** Every child link in [treeId], grouped by union, in one query (FT-004). */
    fun childrenByUnion(treeId: Uuid): Map<UnionId, List<UnionChild>> = transaction {
        UnionChildTable.selectAll().where { UnionChildTable.treeId eq treeId }
            .map { it.toUnionChild() }
            .groupBy { it.unionId }
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

    /**
     * FT-001: replaces the fields of union [id] in [treeId] only while its stored
     * version is still [expectedVersion], incrementing it in the same statement.
     * Returns null, changing nothing, when no row matched (a concurrent writer
     * committed first). Child links keep their own versions and aren't touched.
     */
    fun update(
        treeId: Uuid,
        id: Uuid,
        expectedVersion: Long,
        partner1Id: Uuid,
        partner2Id: Uuid?,
        type: UnionType,
        startDate: GedcomDate?,
        endDate: GedcomDate?,
    ): Union? = transaction {
        val updated = UnionTable.update({
            (UnionTable.id eq id) and (UnionTable.treeId eq treeId) and (UnionTable.version eq expectedVersion)
        }) {
            it[UnionTable.partner1Id] = partner1Id
            it[UnionTable.partner2Id] = partner2Id
            it[UnionTable.type] = type.name
            it[UnionTable.startDateText] = startDate?.displayText
            it[UnionTable.startDateEarliest] = startDate?.earliest?.toJavaLocalDate()
            it[UnionTable.startDateLatest] = startDate?.latest?.toJavaLocalDate()
            it[UnionTable.endDateText] = endDate?.displayText
            it[UnionTable.endDateEarliest] = endDate?.earliest?.toJavaLocalDate()
            it[UnionTable.endDateLatest] = endDate?.latest?.toJavaLocalDate()
            it[UnionTable.version] = expectedVersion + 1
        }
        if (updated == 0) null else findById(treeId, id) ?: error("Union $id was just updated but cannot be read back")
    }

    fun addChild(treeId: Uuid, unionId: Uuid, childId: Uuid, relation: ChildRelation) = transaction {
        UnionChildTable.insert {
            it[UnionChildTable.treeId] = treeId
            it[UnionChildTable.unionId] = unionId
            it[UnionChildTable.childId] = childId
            it[UnionChildTable.relation] = relation.name
            it[UnionChildTable.version] = nextChildVersion()
        }
    }

    /**
     * A child-link version from `union_child_version_seq` (V5): never issued twice,
     * so a deleted and recreated link can't accept the old link's version. Opaque:
     * versions increase but can skip numbers.
     */
    private fun nextChildVersion(): Long = transaction {
        exec("select nextval('union_child_version_seq')") { rs -> rs.next(); rs.getLong(1) }
            ?: error("union_child_version_seq returned no value")
    }

    /** Null when the link doesn't exist in [treeId]; callers treat that as not found. */
    fun findChild(treeId: Uuid, unionId: Uuid, childId: Uuid): UnionChild? = transaction {
        UnionChildTable.selectAll().where {
            (UnionChildTable.unionId eq unionId) and (UnionChildTable.childId eq childId) and (UnionChildTable.treeId eq treeId)
        }.firstOrNull()?.toUnionChild()
    }

    /**
     * FT-001: changes the relation of one child link only while its stored version
     * is still [expectedVersion], replacing it with a fresh sequence value in the
     * same statement. Returns false, changing nothing, when no row matched (the
     * unused sequence value is simply skipped).
     */
    fun updateChild(
        treeId: Uuid,
        unionId: Uuid,
        childId: Uuid,
        expectedVersion: Long,
        relation: ChildRelation,
    ): Boolean = transaction {
        val next = nextChildVersion()
        UnionChildTable.update({
            (UnionChildTable.unionId eq unionId) and (UnionChildTable.childId eq childId) and
                (UnionChildTable.treeId eq treeId) and (UnionChildTable.version eq expectedVersion)
        }) {
            it[UnionChildTable.relation] = relation.name
            it[UnionChildTable.version] = next
        } > 0
    }

    /**
     * FT-007: the unions in [treeId] with any of [personIds] as a partner, ordered by
     * ID and locked `FOR UPDATE`, so concurrent merges and updates lock them in the
     * same order.
     */
    fun lockByPartners(treeId: Uuid, personIds: List<Uuid>): List<Union> = transaction {
        UnionTable.selectAll()
            .where {
                (UnionTable.treeId eq treeId) and
                    ((UnionTable.partner1Id inList personIds) or (UnionTable.partner2Id inList personIds))
            }
            .orderBy(UnionTable.id to SortOrder.ASC)
            .forUpdate(ForUpdateOption.ForUpdate)
            .map { it.toUnion() }
    }

    /** FT-007: the child links in [treeId] of any of [childIds], ordered by key and locked `FOR UPDATE`. */
    fun lockChildLinksOf(treeId: Uuid, childIds: List<Uuid>): List<UnionChild> = transaction {
        UnionChildTable.selectAll()
            .where { (UnionChildTable.treeId eq treeId) and (UnionChildTable.childId inList childIds) }
            .orderBy(UnionChildTable.unionId to SortOrder.ASC, UnionChildTable.childId to SortOrder.ASC)
            .forUpdate(ForUpdateOption.ForUpdate)
            .map { it.toUnionChild() }
    }

    /**
     * FT-007: moves [union]'s partner position(s) held by [from] to [to], only while its
     * version is still the one read, incrementing it in the same statement. Type, dates
     * and child links are unchanged. Null when no row matched.
     */
    fun replacePartner(treeId: Uuid, union: Union, from: Uuid, to: Uuid): Union? = transaction {
        val id = Uuid.parse(union.id.value)
        val updated = UnionTable.update({
            (UnionTable.id eq id) and (UnionTable.treeId eq treeId) and (UnionTable.version eq union.version)
        }) {
            if (Uuid.parse(union.partner1Id.value) == from) it[UnionTable.partner1Id] = to
            if (union.partner2Id?.let { partner -> Uuid.parse(partner.value) } == from) it[UnionTable.partner2Id] = to
            it[UnionTable.version] = union.version + 1
        }
        if (updated == 0) null else findById(treeId, id) ?: error("Union $id was just updated but cannot be read back")
    }

    /**
     * FT-007: moves the child link ([unionId], [fromChildId]) to [toChildId], keeping its
     * relation, only while its version is still [expectedVersion]; the link gets a fresh
     * sequence version (V5) in the same statement. False when no row matched.
     */
    fun retargetChild(treeId: Uuid, unionId: Uuid, fromChildId: Uuid, toChildId: Uuid, expectedVersion: Long): Boolean = transaction {
        val next = nextChildVersion()
        UnionChildTable.update({
            (UnionChildTable.unionId eq unionId) and (UnionChildTable.childId eq fromChildId) and
                (UnionChildTable.treeId eq treeId) and (UnionChildTable.version eq expectedVersion)
        }) {
            it[UnionChildTable.childId] = toChildId
            it[UnionChildTable.version] = next
        } > 0
    }

    /** FT-001: conditional delete of union [id]; see [PersonRepository.delete]. */
    fun delete(treeId: Uuid, id: Uuid, expectedVersion: Long): Int = transaction {
        UnionTable.deleteWhere {
            (UnionTable.id eq id) and (UnionTable.treeId eq treeId) and (UnionTable.version eq expectedVersion)
        }
    }

    /** FT-001: conditional delete of one child link; nothing references a link. */
    fun deleteChild(treeId: Uuid, unionId: Uuid, childId: Uuid, expectedVersion: Long): Int = transaction {
        UnionChildTable.deleteWhere {
            (UnionChildTable.unionId eq unionId) and (UnionChildTable.childId eq childId) and
                (UnionChildTable.treeId eq treeId) and (UnionChildTable.version eq expectedVersion)
        }
    }

    private fun ResultRow.toUnionChild() = UnionChild(
        unionId = UnionId(this[UnionChildTable.unionId].toString()),
        childId = PersonId(this[UnionChildTable.childId].toString()),
        relation = ChildRelation.valueOf(this[UnionChildTable.relation]),
        version = this[UnionChildTable.version],
    )

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
