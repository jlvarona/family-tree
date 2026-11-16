package com.moxietech.family_tree.merge

import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.Sex

/**
 * The profile fields an owner reconciles when merging two records of one person
 * (FT-007). Names stay in their three stored parts, so a merge never concatenates
 * or splits surnames; the result is formatted through `PersonNameFormatter` like
 * any other person.
 */
enum class MergeField {
    GIVEN_NAMES,
    SURNAME_PATERNAL,
    SURNAME_MATERNAL,
    SEX,
    IS_LIVING,
    BIO,
}

/** Whose value of a differing field is kept: the surviving record's or the retired one's. */
enum class MergeSide {
    SURVIVOR,
    RETIRED,
}

/** The editable profile of one person, as compared and merged. */
data class MergeProfile(
    val givenNames: String,
    val surnamePaternal: String?,
    val surnameMaternal: String?,
    val sex: Sex,
    val isLiving: Boolean,
    val bio: String?,
)

/**
 * Relationships that can't survive a merge: the merged person would break a graph
 * invariant, or a link would have two different values with no way to keep both.
 */
enum class MergeConflict {
    /** A union has both people as its partners: the merged person would be both partners. */
    PARTNERS_OF_EACH_OTHER,

    /** One is a partner of a union the other is a child of: the merged person would be their own parent. */
    PARENT_AND_CHILD,

    /** One descends from the other through several generations: merging would close an ancestry loop. */
    ANCESTOR_AND_DESCENDANT,

    /** Both are children of the same union with different relations (e.g. birth and adopted). */
    CHILD_LINK_RELATION_DIFFERS,
}

/**
 * Shared FT-007 rules, used by the server to validate and apply a merge and by
 * clients to show the same comparison and blockers before submitting.
 */
object PersonMerge {

    fun profileOf(person: Person) = MergeProfile(
        givenNames = person.givenNames,
        surnamePaternal = person.surnamePaternal,
        surnameMaternal = person.surnameMaternal,
        sex = person.sex,
        isLiving = person.isLiving,
        bio = person.bio,
    )

    /**
     * The value of [field] as compared. Optional text (surnames, bio) that is null or
     * blank counts as one "empty" value, so a missing surname and an empty one are not
     * a difference; any other difference, including empty against a value, is.
     */
    fun valueOf(profile: MergeProfile, field: MergeField): Any? = when (field) {
        MergeField.GIVEN_NAMES -> profile.givenNames
        MergeField.SURNAME_PATERNAL -> profile.surnamePaternal.emptyAsNull()
        MergeField.SURNAME_MATERNAL -> profile.surnameMaternal.emptyAsNull()
        MergeField.SEX -> profile.sex
        MergeField.IS_LIVING -> profile.isLiving
        MergeField.BIO -> profile.bio.emptyAsNull()
    }

    /** The fields whose values differ, in [MergeField] order; each needs an explicit choice. */
    fun differingFields(survivor: MergeProfile, retired: MergeProfile): Set<MergeField> =
        MergeField.entries.filterTo(LinkedHashSet()) { valueOf(survivor, it) != valueOf(retired, it) }

    /**
     * Problems with [choices] for these [differing] fields: every differing field needs a
     * choice, and a field that is the same in both must not have one (the client was
     * looking at other values). Empty when the choices can be applied.
     */
    fun choiceErrors(differing: Set<MergeField>, choices: Map<MergeField, MergeSide>): List<String> {
        val missing = differing.filter { it !in choices }
        val extra = choices.keys.filter { it !in differing }.sortedBy { it.ordinal }
        return missing.map { "Choose which value of ${it.name} to keep" } +
            extra.map { "${it.name} is the same in both people and takes no choice" }
    }

    /**
     * The survivor's profile after the merge: for each differing field the chosen
     * side's value exactly as stored (an empty value chosen deliberately stays empty),
     * and the survivor's own value everywhere else.
     */
    fun merged(survivor: MergeProfile, retired: MergeProfile, choices: Map<MergeField, MergeSide>): MergeProfile {
        val errors = choiceErrors(differingFields(survivor, retired), choices)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        fun <T> pick(field: MergeField, kept: T, other: T): T = if (choices[field] == MergeSide.RETIRED) other else kept
        return MergeProfile(
            givenNames = pick(MergeField.GIVEN_NAMES, survivor.givenNames, retired.givenNames),
            surnamePaternal = pick(MergeField.SURNAME_PATERNAL, survivor.surnamePaternal, retired.surnamePaternal),
            surnameMaternal = pick(MergeField.SURNAME_MATERNAL, survivor.surnameMaternal, retired.surnameMaternal),
            sex = pick(MergeField.SEX, survivor.sex, retired.sex),
            isLiving = pick(MergeField.IS_LIVING, survivor.isLiving, retired.isLiving),
            bio = pick(MergeField.BIO, survivor.bio, retired.bio),
        )
    }

    /**
     * What stops [survivorId] and [retiredId] from becoming one person, given every
     * union (with its child links) of their tree. Empty when the merge keeps the
     * graph valid. Pre-existing loops that don't run between the two people aren't
     * reported: the merge doesn't introduce them.
     *
     * Two unions with the same partners after the merge, or the merged person being a
     * child of two different unions, are allowed: the existing rules allow both.
     */
    fun relationshipConflicts(survivorId: String, retiredId: String, unions: List<UnionDto>): Set<MergeConflict> {
        val conflicts = LinkedHashSet<MergeConflict>()
        for (union in unions) {
            val partners = listOfNotNull(union.partner1Id, union.partner2Id)
            val children = union.children.associate { it.childId to it.relation }
            if (survivorId in partners && retiredId in partners) conflicts += MergeConflict.PARTNERS_OF_EACH_OTHER
            if ((survivorId in partners && retiredId in children) || (retiredId in partners && survivorId in children)) {
                conflicts += MergeConflict.PARENT_AND_CHILD
            }
            val survivorRelation = children[survivorId]
            val retiredRelation = children[retiredId]
            if (survivorRelation != null && retiredRelation != null && survivorRelation != retiredRelation) {
                conflicts += MergeConflict.CHILD_LINK_RELATION_DIFFERS
            }
        }
        if (MergeConflict.PARENT_AND_CHILD !in conflicts) {
            val parents = parentsByChild(unions)
            if (isAncestor(survivorId, retiredId, parents) || isAncestor(retiredId, survivorId, parents)) {
                conflicts += MergeConflict.ANCESTOR_AND_DESCENDANT
            }
        }
        return conflicts
    }

    private fun parentsByChild(unions: List<UnionDto>): Map<String, Set<String>> {
        val parents = HashMap<String, MutableSet<String>>()
        for (union in unions) {
            val partners = listOfNotNull(union.partner1Id, union.partner2Id)
            for (child in union.children) parents.getOrPut(child.childId) { LinkedHashSet() } += partners
        }
        return parents
    }

    /** Whether [ancestor] is reachable upwards from [person] (visited set, so loops end). */
    private fun isAncestor(ancestor: String, person: String, parents: Map<String, Set<String>>): Boolean {
        val seen = HashSet<String>()
        val queue = ArrayDeque(parents[person].orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next == ancestor) return true
            if (seen.add(next)) queue.addAll(parents[next].orEmpty())
        }
        return false
    }

    private fun String?.emptyAsNull(): String? = this?.takeIf { it.isNotBlank() }
}
