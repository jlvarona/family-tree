package com.moxietech.family_tree.domain

/**
 * Anchor person + direction: the one concept that drives both tree views
 * ("Mom's side") and, from Phase 2 on, who may edit what (a [Branch] is
 * what a [Grant] scopes). The rules for what a branch covers are in
 * [com.moxietech.family_tree.permissions.BranchResolution] (FT-010); the server
 * resolves grants against stored data with a recursive CTE that follows them.
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
