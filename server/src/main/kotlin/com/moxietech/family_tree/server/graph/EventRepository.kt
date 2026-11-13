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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object EventRepository {

    fun findById(id: Uuid): Event? = transaction {
        EventTable.selectAll().where { EventTable.id eq id }.firstOrNull()?.toEvent()
    }

    fun listByPerson(personId: Uuid): List<Event> = transaction {
        EventTable.selectAll().where { EventTable.personId eq personId }.map { it.toEvent() }
    }

    fun listByUnion(unionId: Uuid): List<Event> = transaction {
        EventTable.selectAll().where { EventTable.unionId eq unionId }.map { it.toEvent() }
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
        findById(id) ?: error("Event $id was just inserted but cannot be read back")
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
