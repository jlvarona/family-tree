package com.moxietech.family_tree.api

import kotlinx.serialization.Serializable

/**
 * FT-007: merge two records of the same person in one tree (owner only).
 *
 * The owner names both people explicitly: [survivorId] keeps its ID and receives
 * every relationship, event, membership link and citation of [retiredId], which is
 * then removed. Each version is the one the owner reviewed; if either person changed
 * since, the merge is a `409` and nothing is written.
 *
 * [choices] maps every profile field that differs between the two people
 * (`com.moxietech.family_tree.merge.MergeField` names) to the side whose value is
 * kept (`SURVIVOR` or `RETIRED`). It must name exactly the differing fields: a
 * missing choice, or a choice for a field that is the same in both, is a `400`.
 * Values that are the same in both are kept as they are.
 */
@Serializable
data class MergePeopleRequest(
    val survivorId: String,
    val survivorVersion: Long,
    val retiredId: String,
    val retiredVersion: Long,
    val choices: Map<String, String>,
)

/** The surviving person after the merge, and the ID that no longer exists. */
@Serializable
data class MergePeopleResponse(
    val survivor: PersonDto,
    val retiredId: String,
)
