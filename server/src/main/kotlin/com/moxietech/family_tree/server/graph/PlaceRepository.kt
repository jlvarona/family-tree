package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.domain.Place
import com.moxietech.family_tree.domain.PlaceId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.db.PlaceTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object PlaceRepository {

    fun findById(id: Uuid): Place? = transaction {
        PlaceTable.selectAll().where { PlaceTable.id eq id }.firstOrNull()?.toPlace()
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
            }
            findById(id) ?: error("Place $id was just inserted but cannot be read back")
        }

    private fun ResultRow.toPlace() = Place(
        id = PlaceId(this[PlaceTable.id].toString()),
        treeId = TreeId(this[PlaceTable.treeId].toString()),
        name = this[PlaceTable.name],
        hierarchy = this[PlaceTable.hierarchy],
        latitude = this[PlaceTable.latitude],
        longitude = this[PlaceTable.longitude],
    )
}
