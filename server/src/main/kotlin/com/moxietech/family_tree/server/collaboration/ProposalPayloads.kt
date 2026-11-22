package com.moxietech.family_tree.server.collaboration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** The audit entity type of a change proposal (FT-014). */
internal const val CHANGE_PROPOSAL_ENTITY = "ChangeProposal"

/**
 * FT-014: a write's request body as a proposal's payload, and back. Every key is kept
 * (nulls and defaults included) so per-field approval and diffs see each field, and the
 * request's `version` is left out: the proposal stores the version it was made against
 * separately and approval sends that one.
 */
internal object ProposalPayloads {
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    inline fun <reified T> of(request: T): JsonObject =
        JsonObject((json.encodeToJsonElement(request) as JsonObject) - "version")

    /** The request a proposal replays: [payload] with [version] (when the write takes one). */
    inline fun <reified T> request(payload: JsonObject, version: Long?): T =
        json.decodeFromJsonElement(if (version == null) payload else JsonObject(payload + ("version" to JsonPrimitive(version))))
}
