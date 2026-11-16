package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.domain.Place
import com.moxietech.family_tree.domain.PlaceId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.db.PlaceTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

object PlaceRepository {

    /** Null when [id] doesn't exist or belongs to another tree; callers treat both as not found. */
    fun findById(treeId: Uuid, id: Uuid): Place? = transaction {
        PlaceTable.selectAll().where { (PlaceTable.id eq id) and (PlaceTable.treeId eq treeId) }.firstOrNull()?.toPlace()
    }

    fun listByTree(treeId: Uuid): List<Place> = transaction {
        PlaceTable.selectAll().where { PlaceTable.treeId eq treeId }.map { it.toPlace() }
    }

    fun insert(treeId: Uuid, name: String, hierarchy: String?, latitude: Double?, longitude: Double?): Place =
        transaction {
            val id = Uuid.random()
            PlaceTable.insert {
                it[PlaceTable.id] = id
                it[PlaceTable.treeId] = treeId
                it[PlaceTable.name] = name
                it[PlaceTable.hierarchy] = hierarchy
                it[PlaceTable.latitude] = latitude
                it[PlaceTable.longitude] = longitude
                it[PlaceTable.version] = 1
            }
            findById(treeId, id) ?: error("Place $id was just inserted but cannot be read back")
        }

    /**
     * FT-001: replaces the fields of place [id] in [treeId] only while its stored
     * version is still [expectedVersion], incrementing it in the same statement.
     * Returns null, changing nothing, when no row matched (a concurrent writer
     * committed first).
     */
    fun update(
        treeId: Uuid,
        id: Uuid,
        expectedVersion: Long,
        name: String,
        hierarchy: String?,
        latitude: Double?,
        longitude: Double?,
    ): Place? = transaction {
        val updated = PlaceTable.update({
            (PlaceTable.id eq id) and (PlaceTable.treeId eq treeId) and (PlaceTable.version eq expectedVersion)
        }) {
            it[PlaceTable.name] = name
            it[PlaceTable.hierarchy] = hierarchy
            it[PlaceTable.latitude] = latitude
            it[PlaceTable.longitude] = longitude
            it[PlaceTable.version] = expectedVersion + 1
        }
        if (updated == 0) null else findById(treeId, id) ?: error("Place $id was just updated but cannot be read back")
    }

    private fun ResultRow.toPlace() = Place(
        id = PlaceId(this[PlaceTable.id].toString()),
        treeId = TreeId(this[PlaceTable.treeId].toString()),
        name = this[PlaceTable.name],
        hierarchy = this[PlaceTable.hierarchy],
        latitude = this[PlaceTable.latitude],
        longitude = this[PlaceTable.longitude],
        version = this[PlaceTable.version],
    )
}
