package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.domain.Event
import com.moxietech.family_tree.domain.EventId
import com.moxietech.family_tree.domain.EventType
import com.moxietech.family_tree.domain.GedcomDate
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.PlaceId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.server.db.EventTable
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

object EventRepository {

    /** Null when [id] doesn't exist or belongs to another tree; callers treat both as not found. */
    fun findById(treeId: Uuid, id: Uuid): Event? = transaction {
        EventTable.selectAll().where { (EventTable.id eq id) and (EventTable.treeId eq treeId) }.firstOrNull()?.toEvent()
    }

    fun listByPerson(treeId: Uuid, personId: Uuid): List<Event> = transaction {
        EventTable.selectAll().where { (EventTable.personId eq personId) and (EventTable.treeId eq treeId) }
            .map { it.toEvent() }
    }

    fun listByUnion(treeId: Uuid, unionId: Uuid): List<Event> = transaction {
        EventTable.selectAll().where { (EventTable.unionId eq unionId) and (EventTable.treeId eq treeId) }
            .map { it.toEvent() }
    }

    fun insert(
        treeId: Uuid,
        personId: Uuid?,
        unionId: Uuid?,
        type: EventType,
        date: GedcomDate?,
        placeId: Uuid?,
        notes: String?,
    ): Event = transaction {
        val id = Uuid.random()
        EventTable.insert {
            it[EventTable.id] = id
            it[EventTable.treeId] = treeId
            it[EventTable.personId] = personId
            it[EventTable.unionId] = unionId
            it[EventTable.type] = type.name
            it[EventTable.dateText] = date?.displayText
            it[EventTable.dateEarliest] = date?.earliest?.toJavaLocalDate()
            it[EventTable.dateLatest] = date?.latest?.toJavaLocalDate()
            it[EventTable.placeId] = placeId
            it[EventTable.notes] = notes
            it[EventTable.version] = 1
        }
        findById(treeId, id) ?: error("Event $id was just inserted but cannot be read back")
    }

    /**
     * FT-001: replaces the fields of event [id] in [treeId] only while its stored
     * version is still [expectedVersion], incrementing it in the same statement.
     * Returns null, changing nothing, when no row matched (a concurrent writer
     * committed first).
     */
    fun update(
        treeId: Uuid,
        id: Uuid,
        expectedVersion: Long,
        personId: Uuid?,
        unionId: Uuid?,
        type: EventType,
        date: GedcomDate?,
        placeId: Uuid?,
        notes: String?,
    ): Event? = transaction {
        val updated = EventTable.update({
            (EventTable.id eq id) and (EventTable.treeId eq treeId) and (EventTable.version eq expectedVersion)
        }) {
            it[EventTable.personId] = personId
            it[EventTable.unionId] = unionId
            it[EventTable.type] = type.name
            it[EventTable.dateText] = date?.displayText
            it[EventTable.dateEarliest] = date?.earliest?.toJavaLocalDate()
            it[EventTable.dateLatest] = date?.latest?.toJavaLocalDate()
            it[EventTable.placeId] = placeId
            it[EventTable.notes] = notes
            it[EventTable.version] = expectedVersion + 1
        }
        if (updated == 0) null else findById(treeId, id) ?: error("Event $id was just updated but cannot be read back")
    }

    private fun ResultRow.toEvent() = Event(
        id = EventId(this[EventTable.id].toString()),
        treeId = TreeId(this[EventTable.treeId].toString()),
        personId = this[EventTable.personId]?.let { PersonId(it.toString()) },
        unionId = this[EventTable.unionId]?.let { UnionId(it.toString()) },
        type = EventType.valueOf(this[EventTable.type]),
        date = this[EventTable.dateText]?.let { text ->
            GedcomDate(
                displayText = text,
                earliest = this[EventTable.dateEarliest]?.toKotlinLocalDate(),
                latest = this[EventTable.dateLatest]?.toKotlinLocalDate(),
            )
        },
        placeId = this[EventTable.placeId]?.let { PlaceId(it.toString()) },
        notes = this[EventTable.notes],
        version = this[EventTable.version],
    )
}
