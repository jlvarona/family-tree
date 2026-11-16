package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.domain.Event
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.Place
import com.moxietech.family_tree.domain.Union
import com.moxietech.family_tree.server.errors.NotFoundException
import kotlin.uuid.Uuid

/**
 * Tenant isolation (FT-065, AGENTS.md "Tenant isolation"): resolves an ID
 * that a caller supplied for the tree they're authorized for. An ID from
 * another tree gets exactly the same NotFoundException (same status, same
 * message) as an ID that doesn't exist, so a response never reveals that a
 * row exists elsewhere. Call these before any write that references the ID.
 */
internal object TreeScopedLookups {

    fun requirePerson(treeId: Uuid, personId: Uuid): Person =
        PersonRepository.findById(treeId, personId) ?: throw NotFoundException("No person with id $personId")

    fun requireUnion(treeId: Uuid, unionId: Uuid): Union =
        UnionRepository.findById(treeId, unionId) ?: throw NotFoundException("No union with id $unionId")

    fun requirePlace(treeId: Uuid, placeId: Uuid): Place =
        PlaceRepository.findById(treeId, placeId) ?: throw NotFoundException("No place with id $placeId")

    fun requireEvent(treeId: Uuid, eventId: Uuid): Event =
        EventRepository.findById(treeId, eventId) ?: throw NotFoundException("No event with id $eventId")
}
