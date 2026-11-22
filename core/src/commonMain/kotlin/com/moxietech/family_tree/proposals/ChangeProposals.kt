package com.moxietech.family_tree.proposals

import com.moxietech.family_tree.domain.GrantRole
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.permissions.EditAuthority
import com.moxietech.family_tree.permissions.GrantRules
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * FT-014: a change a member could not make live, kept as a proposal for review
 * (concept-architecture.md "Edit approval"; decisions table "Change proposals (FT-014)").
 *
 * Each operation is one of the existing versioned graph writes. Its proposed values are
 * the write's own request body (without `version`), and approval replays the same write
 * on the reviewer's behalf, so it is validated, tenant-checked, locked and audited exactly
 * like a live write. [fields] are the parts a reviewer may approve separately: updates
 * split into independent fields, with values that only make sense together (a union's
 * partners, its start and end dates, an event's person or union) kept in one group;
 * creates and deletes are one [ProposalField.RECORD].
 */
enum class ProposalOperation(val fields: List<ProposalField>) {
    UPDATE_PERSON(
        listOf(
            ProposalField.GIVEN_NAMES,
            ProposalField.SURNAME_PATERNAL,
            ProposalField.SURNAME_MATERNAL,
            ProposalField.SEX,
            ProposalField.IS_LIVING,
            ProposalField.BIO,
        ),
    ),
    DELETE_PERSON(listOf(ProposalField.RECORD)),
    CREATE_UNION(listOf(ProposalField.RECORD)),
    UPDATE_UNION(listOf(ProposalField.PARTNERS, ProposalField.UNION_TYPE, ProposalField.UNION_DATES)),
    DELETE_UNION(listOf(ProposalField.RECORD)),
    ADD_CHILD(listOf(ProposalField.RECORD)),
    UPDATE_CHILD(listOf(ProposalField.RELATION)),
    DELETE_CHILD(listOf(ProposalField.RECORD)),
    CREATE_EVENT(listOf(ProposalField.RECORD)),
    UPDATE_EVENT(
        listOf(
            ProposalField.EVENT_SUBJECT,
            ProposalField.EVENT_TYPE,
            ProposalField.EVENT_DATE,
            ProposalField.EVENT_PLACE,
            ProposalField.EVENT_NOTES,
        ),
    ),
    DELETE_EVENT(listOf(ProposalField.RECORD)),
    CREATE_CITATION(listOf(ProposalField.RECORD)),
    ;

    /** Whether approval replaces only the approved fields of a stored record ([ProposalFields.compose]). */
    val isPerField: Boolean get() = fields != listOf(ProposalField.RECORD)

    /** Whether the target is an existing record whose version the proposal was made against. */
    val hasBaseVersion: Boolean get() = name.startsWith("UPDATE_") || name.startsWith("DELETE_")
}

/**
 * One approvable part of a proposal and the request keys it carries. [RECORD] is the whole
 * request (every key), for creates and deletes.
 */
enum class ProposalField(val keys: List<String>) {
    RECORD(emptyList()),
    GIVEN_NAMES(listOf("givenNames")),
    SURNAME_PATERNAL(listOf("surnamePaternal")),
    SURNAME_MATERNAL(listOf("surnameMaternal")),
    SEX(listOf("sex")),
    IS_LIVING(listOf("isLiving")),
    BIO(listOf("bio")),

    /** A union's topology: both partner positions together. */
    PARTNERS(listOf("partner1Id", "partner2Id")),
    UNION_TYPE(listOf("type")),

    /** Start and end are validated as an ordered pair, so they are approved together. */
    UNION_DATES(listOf("startDate", "endDate")),
    RELATION(listOf("relation")),

    /** Exactly one of person or union: the event's subject is one value. */
    EVENT_SUBJECT(listOf("personId", "unionId")),
    EVENT_TYPE(listOf("type")),
    EVENT_DATE(listOf("date")),
    EVENT_PLACE(listOf("placeId")),
    EVENT_NOTES(listOf("notes")),
}

enum class ProposalStatus { PENDING, APPLIED, REJECTED, WITHDRAWN }

/** Why the change was queued instead of applied (the refusal FT-012 and FT-013 would have given). */
enum class ProposalReason {
    /** A person or relationship outside the branches the proposer can edit live. */
    OUTSIDE_BRANCH,

    /** The change would let the proposer edit people they couldn't before. */
    WIDENS_REACH,

    /** The change would reveal a living person's details hidden from the proposer. */
    REVEALS_PRIVATE,
}

object ProposalFields {

    const val NO_FIELDS_MESSAGE = "Approve at least one field of the proposal"

    fun unknownFieldMessage(field: String) = "Unknown or inapplicable proposal field '$field'"

    /**
     * The fields to apply when a reviewer approves [requested] (null: every field) of a
     * proposal for [operation]; errors are 400 messages. Names are matched exactly.
     */
    fun approved(operation: ProposalOperation, requested: List<String>?): Result {
        if (requested == null) return Result(operation.fields.toSet(), emptyList())
        if (requested.isEmpty()) return Result(emptySet(), listOf(NO_FIELDS_MESSAGE))
        val errors = mutableListOf<String>()
        val fields = mutableSetOf<ProposalField>()
        for (name in requested) {
            val field = ProposalField.entries.firstOrNull { it.name == name }
            if (field == null || field !in operation.fields) errors += unknownFieldMessage(name) else fields += field
        }
        return Result(fields, errors)
    }

    data class Result(val fields: Set<ProposalField>, val errors: List<String>)

    /** The request keys [field] carries in [proposed] ([ProposalField.RECORD]: every key). */
    fun keysOf(field: ProposalField, proposed: JsonObject): List<String> =
        if (field == ProposalField.RECORD) proposed.keys.toList() else field.keys

    /** The part of [values] that belongs to [field]; a missing key is JSON null. */
    fun slice(field: ProposalField, values: JsonObject): JsonObject =
        JsonObject(keysOf(field, values).associateWith { values[it] ?: JsonNull })

    /**
     * The request a per-field approval applies: every [approved] field from [proposed],
     * every other field as the record is stored now ([current], in the same request shape).
     * The caller then sends it at the proposal's base version, so the fields not approved
     * stay exactly as the proposer saw them, or the write is a stale `409`.
     */
    fun compose(operation: ProposalOperation, approved: Set<ProposalField>, proposed: JsonObject, current: JsonObject): JsonObject {
        require(operation.isPerField) { "$operation is approved as a whole" }
        val values = current.toMutableMap()
        for (field in operation.fields) {
            if (field in approved) for (key in field.keys) values[key] = proposed[key] ?: JsonNull
        }
        return JsonObject(values)
    }

    /** Whether [field] differs between [proposed] and [current] (missing and null are equal). */
    fun differs(field: ProposalField, proposed: JsonObject, current: JsonObject): Boolean =
        keysOf(field, proposed).any { key -> (proposed[key] ?: JsonNull) != (current[key] ?: JsonNull) }
}

/**
 * FT-014: who may review (approve or reject) a proposal, decided from data read under
 * the review's locks. Concept doc: "approved by the owner of that branch or the tree
 * owner. A branch owner approves only inside the branch they own"; decisions table
 * "Approvers": "Branch owner (only for their branch) or tree owner".
 *
 * - The tree owner reviews every proposal.
 * - A member reviews a proposal only when **every** person it affects (its subjects:
 *   the person, the partners and child of a relationship, an event's or citation's
 *   people, before and after the change) is covered by one of their effective
 *   BRANCH_OWNER grants. A cross-branch change therefore needs a branch owner of all its
 *   subjects, or the tree owner.
 * - A whole-tree ADMIN is not a reviewer by that role (the role table gives approval to
 *   the owner and branch owners only); CONTRIBUTOR and VIEWER grants never review.
 * - A proposer never reviews their own proposal (they can withdraw it).
 */
class ReviewAuthority private constructor(
    val isOwner: Boolean,
    private val owned: Set<PersonId>,
) {
    /** Whether this member reviews a change affecting [subjects]. */
    fun canReview(subjects: Collection<PersonId>): Boolean = isOwner || (subjects.isNotEmpty() && owned.containsAll(subjects))

    /** Whether this member reviews anything at all. */
    val isReviewer: Boolean get() = isOwner || owned.isNotEmpty()

    companion object {
        fun owner(): ReviewAuthority = ReviewAuthority(true, emptySet())

        /** A non-owner: [grants] are every grant they hold with its resolved scope. */
        fun member(grants: List<EditAuthority.ScopedGrant>): ReviewAuthority = ReviewAuthority(
            isOwner = false,
            owned = grants
                .filter { GrantRules.effectiveRole(it.role, it.branch) == GrantRole.BRANCH_OWNER }
                .flatMapTo(mutableSetOf()) { it.scope },
        )
    }
}
