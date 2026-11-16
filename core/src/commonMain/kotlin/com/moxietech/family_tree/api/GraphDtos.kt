package com.moxietech.family_tree.api

import kotlinx.serialization.Serializable

/**
 * The "whole tree" bundle (FT-004): every person and every union (with its
 * children) of one tree in a single response, already shaped for the caller's
 * living-person privacy, so a client can lay out the full tree from one call.
 */
@Serializable
data class TreeGraphDto(
    val treeId: String,
    val people: List<PersonDto>,
    val unions: List<UnionDto>,
)
