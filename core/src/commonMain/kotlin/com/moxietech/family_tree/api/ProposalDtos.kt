package com.moxietech.family_tree.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * FT-014: a change proposal as one caller may see it. [operation], [status], [reason] and
 * every [ProposalFieldDto.field] are `com.moxietech.family_tree.proposals` enum names.
 *
 * The server shapes it for the caller (decisions table "Change proposals (FT-014)"):
 * proposed values are the proposer's own input; [ProposalFieldDto.current] (the record as
 * stored now), [ProposalFieldDto.changed] and [stale] appear only when the caller can see
 * every person the record is about, so a proposer never learns values they can't read.
 * The people a proposal affects are not listed.
 *
 * Returned with `202 Accepted` by a write that was queued instead of applied.
 */
@Serializable
data class ChangeProposalDto(
    val id: String,
    val treeId: String,
    val operation: String,
    /** The record the write names: person, union or event ID, `unionId:childId`, or the union of a new child link; null for creates. */
    val targetId: String? = null,
    val status: String,
    val reason: String,
    val proposerAccountId: String,
    val reviewerAccountId: String? = null,
    val createdAt: String,
    val decidedAt: String? = null,
    val fields: List<ProposalFieldDto>,
    /** The fields applied, once [status] is `APPLIED`. */
    val approvedFields: List<String>? = null,
    /** True when the target changed since the proposal was made (approval would be a `409`); shown only with [ProposalFieldDto.current]. */
    val stale: Boolean? = null,
    /** Whether the caller may approve or reject it. */
    val canReview: Boolean = false,
    /** Whether the caller may withdraw it (the proposer, while pending). */
    val canWithdraw: Boolean = false,
    val version: Long,
    /**
     * Places, sources and events the proposal names (its target and the IDs in the values
     * shown), so a client can say which record is meant without showing IDs or fetching
     * anything. Shaped like reads: places and sources (tree-wide records every member can
     * list) always; an event only while the caller can see every person it is about.
     * People and unions aren't repeated: they are in the tree bundle.
     */
    val references: List<ProposalReferenceDto> = emptyList(),
)

/**
 * FT-014: a record a proposal names. [kind] is `PLACE` ([name], [detail] = hierarchy),
 * `SOURCE` ([name] = title, [detail] = source type) or `EVENT` ([eventType], [eventDate]
 * as typed, and its subject: [personId] or [unionId]).
 */
@Serializable
data class ProposalReferenceDto(
    val id: String,
    val kind: String,
    val name: String? = null,
    val detail: String? = null,
    val eventType: String? = null,
    val eventDate: String? = null,
    val personId: String? = null,
    val unionId: String? = null,
)

/** One approvable part of a proposal: the proposed values of its request keys, and the stored ones when visible. */
@Serializable
data class ProposalFieldDto(
    val field: String,
    val proposed: JsonObject,
    val current: JsonElement? = null,
    val changed: Boolean? = null,
)

/**
 * FT-014: what the caller may do in one tree, decided by the server from their membership
 * and grants (`GET /api/v1/trees/{treeId}/capabilities`), so clients show the controls a
 * member can actually use without recomputing grant scopes. Display only: every write is
 * still authorized by the server.
 * - [canEdit]: may add people and change something somewhere (owner or an editing grant);
 *   changes outside their live reach are queued when [proposals] is on.
 * - [canWriteTreeWide]: owner or whole-tree admin (places, sources, media uploads).
 * - [canReview]: owner or a branch owner, while [proposals] is on.
 * - [proposals]: the sharing workflow (invitations and change proposals) is enabled.
 */
@Serializable
data class TreeCapabilitiesDto(
    val treeId: String,
    val isOwner: Boolean,
    val canEdit: Boolean,
    val canWriteTreeWide: Boolean,
    val canReview: Boolean,
    val proposals: Boolean,
)

/** Approve [fields] (null: all of them) of a pending proposal at the proposal's [version]. */
@Serializable
data class ApproveProposalRequest(
    val version: Long,
    val fields: List<String>? = null,
)

/** Reject or withdraw a pending proposal at its [version]. */
@Serializable
data class DecideProposalRequest(val version: Long)
