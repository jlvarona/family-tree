package com.moxietech.family_tree.domain

/**
 * Connects two partners (GEDCOM FAM-style). Relationships always go through a
 * [Union] + [UnionChild], never through direct parent/child columns on
 * [Person] — this is what lets a person have more than one union
 * (remarriage, half-siblings) without special-casing the schema.
 */
data class Union(
    val id: UnionId,
    val treeId: TreeId,
    val partner1Id: PersonId,
    val partner2Id: PersonId?,
    val type: UnionType,
    val startDate: GedcomDate?,
    val endDate: GedcomDate?,
    val version: Long,
)

enum class UnionType {
    MARRIED,
    DIVORCED,
    PARTNERS,
    UNKNOWN,
}

data class UnionChild(
    val unionId: UnionId,
    val childId: PersonId,
    val relation: ChildRelation,
    val version: Long,
)

enum class ChildRelation {
    BIRTH,
    ADOPTED,
    STEP,
    FOSTER,
}

data class UnionId(val value: String)
