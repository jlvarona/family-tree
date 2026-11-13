package com.moxietech.family_tree.domain

/**
 * Anchor person + direction: the one concept that drives both tree views
 * ("Mom's side") and, from Phase 2 on, who may edit what (a [Branch] is
 * what a Grant scopes). Resolving a [Branch] to the set of person ids it
 * covers is a recursive graph query over Union/UnionChild and lives in the
 * server repository layer, not here — this type only carries the
 * definition, since that's what server and clients both need to agree on.
 */
data class Branch(
    val anchorPersonId: PersonId,
    val direction: BranchDirection,
    val depth: Int?,
    val includePartners: Boolean,
) {
    init {
        require(depth == null || depth > 0) { "depth must be null (unlimited) or positive" }
    }
}

enum class BranchDirection {
    ANCESTORS,
    DESCENDANTS,
    /** Ancestors of the anchor, plus all of their descendants — e.g. "maternal side". */
    SIDE,
    WHOLE_TREE,
}
