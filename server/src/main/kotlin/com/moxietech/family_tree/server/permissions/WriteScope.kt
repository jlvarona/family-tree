package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.api.ChangeProposalDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.Grant
import com.moxietech.family_tree.domain.Membership
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.permissions.EditAccess
import com.moxietech.family_tree.permissions.EditAuthority
import com.moxietech.family_tree.permissions.PersonVisibility
import com.moxietech.family_tree.proposals.ProposalOperation
import com.moxietech.family_tree.proposals.ProposalReason
import com.moxietech.family_tree.proposals.ReviewAuthority
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.collaboration.CHANGE_PROPOSAL_ENTITY
import com.moxietech.family_tree.server.collaboration.ChangeProposalAuditDto
import com.moxietech.family_tree.server.collaboration.ChangeProposalRepository
import com.moxietech.family_tree.server.collaboration.ChangeProposalViews
import com.moxietech.family_tree.server.collaboration.toAuditDto
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.graph.PersonRepository
import com.moxietech.family_tree.server.graph.ViewerPolicy
import com.moxietech.family_tree.server.trees.TreeService
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/** FT-014: how a write's authorization checks treat a change the caller can't make live. */
internal enum class WriteMode {
    /** FT-012/FT-013: refused with a 403 and nothing written (proposals off). */
    DIRECT,

    /** Queued as a proposal instead (proposals on). */
    PROPOSE,

    /** A reviewer replaying an approved proposal: every affected person must be in their review scope. */
    REVIEW,
}

/** FT-014: what a write did: applied live, or queued as a proposal. */
internal sealed interface WriteOutcome<out T> {
    data class Applied<T>(val value: T) : WriteOutcome<T>

    data class Proposed(val proposal: ChangeProposalDto) : WriteOutcome<Nothing>
}

/** The applied value; a [WriteMode.DIRECT] or [WriteMode.REVIEW] write never proposes. */
internal fun <T> WriteOutcome<T>.applied(): T = when (this) {
    is WriteOutcome.Applied -> value
    is WriteOutcome.Proposed -> error("A direct write was queued as a proposal")
}

/** FT-014: the replayable form of a write: its operation, the record its path names, the version read and its body. */
internal data class ProposalDraft(
    val operation: ProposalOperation,
    val targetId: String?,
    val baseVersion: Long?,
    val payload: JsonObject,
)

/** Raised inside [WriteScope.tentatively] when a [WriteMode.PROPOSE] change turns out to need review. */
internal class ProposalNeeded(val reason: ProposalReason) : RuntimeException(null, null, false, false)

/**
 * FT-012: one write's caller and their [EditAuthority], valid only inside the
 * transaction [WriteAccess.begin] (or [WriteAccess.beginReview]) started. Every check
 * throws a [ForbiddenException] that rolls the transaction back.
 *
 * FT-014 ([mode]):
 * - [WriteMode.PROPOSE]: a check that FT-012/FT-013 would refuse with "outside the
 *   branch" or "reveals private details" marks the write as pending instead, and the
 *   write goes on validating the request (400, 404, stale 409 as usual) until
 *   [tentatively], which stores a proposal instead of changing anything. A change
 *   refused only after it was made (it widens the caller's reach or reveals a hidden
 *   person, [requireNoGainedReach]) is rolled back to a savepoint and stored as a
 *   proposal too. A caller who can't edit anywhere ([EditAccess.NONE]), an event hidden
 *   from them (the missing-event 404) and tree-wide operations ([requireTreeWide]) are
 *   refused as before. Every person a check touched is a subject of the proposal.
 * - [WriteMode.REVIEW]: a reviewer replaying a proposal. Every person a check touches
 *   must be in their review scope ([ReviewAuthority]: the owner, or every person inside
 *   one of their BRANCH_OWNER grants), not just one end of a relationship; the reviewer's
 *   own reach and reveal checks apply, and a branch owner's approval must not widen the
 *   proposer's access either ([attachProposer]).
 */
internal class WriteScope(
    val tree: Tree,
    private val treeUuid: Uuid,
    val caller: AccountId,
    private val membership: Membership,
    private val grants: List<Grant>,
    scopedGrants: List<EditAuthority.ScopedGrant>,
    private val authority: EditAuthority,
    val mode: WriteMode = WriteMode.DIRECT,
) {
    val isOwner: Boolean get() = authority.isOwner
    val isTreeWide: Boolean get() = authority.isTreeWide

    /** FT-013: what the caller could see when the write began, from the grants it locked and their scopes then. */
    private val visibleBefore: PersonVisibility =
        if (authority.isOwner) PersonVisibility.owner() else PersonVisibility.member(scopedGrants)

    /** FT-014: whom this caller reviews (owner, or the people of their BRANCH_OWNER grants), from the same locked grants. */
    val review: ReviewAuthority =
        if (authority.isOwner) ReviewAuthority.owner() else ReviewAuthority.member(scopedGrants)

    /** Set by [requireNoGainedReach]: what the caller sees after this write's graph change. */
    private var visibleAfter: PersonVisibility? = null

    /** FT-014: every person a check touched (the proposal's subjects). */
    private val subjects = linkedSetOf<Uuid>()

    /** FT-014 [WriteMode.PROPOSE]: why the write must be reviewed, once a check said so. */
    private var pending: ProposalReason? = null

    /** FT-014 [WriteMode.REVIEW]: the proposer, for a branch owner's approval. */
    private var proposer: WriteAccess.ProposerShadow? = null

    /**
     * FT-013: what this caller may see, for refusals that must not reveal a hidden fact,
     * for the reveal checks and for write responses shaped like reads. The owner sees
     * everyone; anyone else through the scopes of the grants [WriteAccess.begin] locked,
     * computed once at the start under the exclusive graph lock (no new read). After a
     * graph change ([requireNoGainedReach]) only people visible both before and after it
     * count, so a response never shows more than either state allows. The home branch
     * reveals no one.
     */
    fun viewer(): ViewerPolicy = ViewerPolicy(visibleAfter?.let(visibleBefore::both) ?: visibleBefore)

    /**
     * FT-013: 403 when [person] is living, hidden from this caller, and the change would
     * mark them deceased, which would expose their earlier photo, events and citations to
     * every member. Editors who can see the person change it as usual. FT-014: a proposal
     * in [WriteMode.PROPOSE].
     */
    fun requireNotRevealedByDeath(person: Person, becomesLiving: Boolean) {
        if (person.isLiving && !becomesLiving && !visibleBefore.canSeeDetails(person)) {
            revealRefused(listOf(Uuid.parse(person.id.value)))
        }
        // FT-014 (user decision 2026-11-22): only the tree owner approves a change that
        // widens the proposer's access. Marking a person deceased reveals them to every
        // member, so a branch owner may approve it only if the proposer already sees them.
        val shadow = proposer
        if (shadow != null && person.isLiving && !becomesLiving && !shadow.visibility.canSeeDetails(person)) {
            throw ForbiddenException(WriteAccess.reviewWidensProposerMessage(tree.id.value))
        }
    }

    /**
     * FT-013: 403 when a union or an event moves away from a person hidden from this
     * caller. Its ID, events, citations and scans stay with it and can describe the person
     * it leaves, so they would become readable through the remaining or new people. Changes
     * that keep the same people are shaped by the read policy as usual. FT-014: a proposal
     * in [WriteMode.PROPOSE].
     */
    fun requireNoHiddenSubjectRemoved(before: List<Person>, afterIds: Collection<Uuid>) {
        val after = afterIds.mapTo(mutableSetOf()) { it.toString() }
        val removedHidden = before.filter { it.id.value !in after && !visibleBefore.canSeeDetails(it) }
        if (removedHidden.isNotEmpty()) revealRefused(removedHidden.map { Uuid.parse(it.id.value) })
        requireProposerNotExposedByMove(before, afterIds)
    }

    /**
     * FT-014 (user decision 2026-11-22), a branch owner's approval: the union or event keeps
     * its ID, dates, events, citations and scans. If it leaves a person hidden from the
     * proposer and every person it is about afterwards is visible to them, those facts
     * become readable to the proposer: an expansion only the tree owner approves. Scoped to
     * facts actually newly exposed: a move that keeps a hidden subject, or lands on someone
     * the proposer can't see either, exposes nothing and is allowed. (People a graph change
     * reveals are refused by [requireNoGainedReach].)
     */
    private fun requireProposerNotExposedByMove(before: List<Person>, afterIds: Collection<Uuid>) {
        val shadow = proposer ?: return
        val after = afterIds.mapTo(mutableSetOf()) { it.toString() }
        val leavesHidden = before.any { it.id.value !in after && !shadow.visibility.canSeeDetails(it) }
        if (!leavesHidden) return
        val afterPeople = afterIds.map { PersonRepository.findById(treeUuid, it) ?: return }
        if (afterPeople.all { shadow.visibility.canSeeDetails(it) }) {
            throw ForbiddenException(WriteAccess.reviewWidensProposerMessage(tree.id.value))
        }
    }

    private fun revealRefused(people: List<Uuid>) {
        subjects += people
        when (mode) {
            WriteMode.PROPOSE -> markPending(ProposalReason.REVEALS_PRIVATE)
            WriteMode.REVIEW -> requireReviewable(people)
            WriteMode.DIRECT -> throw ForbiddenException(WriteAccess.revealsPrivateMessage(tree.id.value))
        }
    }

    fun personAccess(person: Uuid): EditAccess = authority.personAccess(person.toPersonId())

    fun relationshipAccess(people: Collection<Uuid>): EditAccess = authority.relationshipAccess(people.map { it.toPersonId() })

    /** Live access to [person], or 403 (FT-014: or a proposal; in review, [person] must be reviewable). */
    fun requirePerson(person: Uuid) = decide(listOf(person), personAccess(person))

    /**
     * Live access to at least one of [people] (a union's partners, a child link's parents
     * and child), or 403 (FT-014: or a proposal; in review, every one must be reviewable).
     */
    fun requireRelationship(people: Collection<Uuid>) = decide(people, relationshipAccess(people))

    /**
     * A fact about [subjects] (an event: its person or its union's partners; a citation
     * target's people): live access to at least one of them, or 403. When refused and
     * [missing] is given, a fact the caller can't see at all ([ViewerPolicy.canSeeAll],
     * the rule every read uses to omit it) throws [missing] instead, so the refusal
     * doesn't confirm that a private event exists (as the FT-002 citation route does).
     * FT-014: that 404 comes before any proposal, so a hidden event is never queued.
     */
    fun requireFact(subjects: List<Person>, missing: (() -> Throwable)? = null) {
        val ids = subjects.map { Uuid.parse(it.id.value) }
        if (mode != WriteMode.REVIEW) {
            val access = relationshipAccess(ids)
            if (access == EditAccess.LIVE) {
                this.subjects += ids
                return
            }
            if (missing != null && !viewer().canSeeAll(subjects)) throw missing()
            decide(ids, access)
        } else {
            if (missing != null && !viewer().canSeeAll(subjects)) throw missing()
            decide(ids, EditAccess.LIVE)
        }
    }

    /** Whether this caller may attach media: tree-wide members only (FT-012). Never a proposal (FT-014). */
    fun requireTreeWide() {
        if (!isTreeWide) throw ForbiddenException(TreeService.treeWideOnlyMessage(tree.id.value))
    }

    /** FT-014: 403 unless this reviewer reviews a change affecting [people]. */
    fun requireReviewable(people: Collection<Uuid>) {
        if (!review.canReview(people.map { it.toPersonId() })) {
            throw ForbiddenException(WriteAccess.outsideReviewMessage(tree.id.value))
        }
    }

    /**
     * FT-014 review: remember the proposer, so a branch owner's approval can't widen their
     * access ([requireNoGainedReach]). The tree owner's approval may (it is theirs to grant).
     * Call after the proposal's row lock, before replaying the write.
     */
    fun attachProposer(proposerUuid: Uuid) {
        check(mode == WriteMode.REVIEW)
        if (!isOwner) proposer = WriteAccess.proposerShadow(tree, treeUuid, proposerUuid)
    }

    /**
     * After a graph change in this transaction (and its generation advance), under the same
     * exclusive graph lock and locked grants: 403 when the caller could now edit anyone live
     * they couldn't before (FT-012), or see the details of a living person who was hidden
     * from them (FT-013: a VIEWER grant with partners, say, would reveal a new partner of
     * someone the caller can edit). Either rolls the change back. The caller's own
     * unattached creations are exempt from the read check, so create-then-attach still
     * works: they entered those details themselves. Nothing to check for tree-wide callers,
     * who edit and see everyone either way.
     *
     * FT-014: in [WriteMode.PROPOSE] the change is rolled back to its savepoint and queued
     * ([tentatively]); in [WriteMode.REVIEW] a branch owner's approval is also refused when
     * it would do the same for the proposer.
     */
    fun requireNoGainedReach() {
        val shadow = proposer
        if (authority.isTreeWide && shadow == null) return
        val people = WriteAccess.peopleOf(treeUuid)
        val everyone = people.keys.toPersonIds()
        val living = people.filterValues { it }.keys.toPersonIds()
        if (!authority.isTreeWide) {
            val scopedAfter = WriteAccess.scopedGrantsFor(treeUuid, grants)
            val after = WriteAccess.authorityFor(treeUuid, Uuid.parse(caller.value), membership, scopedAfter)
            if (authority.gainedBy(after, everyone).isNotEmpty()) {
                if (mode == WriteMode.PROPOSE) throw ProposalNeeded(ProposalReason.WIDENS_REACH)
                throw ForbiddenException(WriteAccess.widensReachMessage(tree.id.value))
            }
            val seenAfter = PersonVisibility.member(scopedAfter)
            if (visibleBefore.revealedBy(seenAfter, living, exempt = authority.ownUnattachedEditable).isNotEmpty()) {
                if (mode == WriteMode.PROPOSE) throw ProposalNeeded(ProposalReason.REVEALS_PRIVATE)
                throw ForbiddenException(WriteAccess.revealsPrivateMessage(tree.id.value))
            }
            visibleAfter = seenAfter
        }
        if (shadow != null) {
            val scopedAfter = WriteAccess.scopedGrantsFor(treeUuid, shadow.grants)
            val after = WriteAccess.authorityFor(treeUuid, shadow.account, shadow.membership, scopedAfter)
            val widens = shadow.authority.gainedBy(after, everyone).isNotEmpty() ||
                shadow.visibility.revealedBy(PersonVisibility.member(scopedAfter), living, exempt = shadow.authority.ownUnattachedEditable)
                    .isNotEmpty()
            if (widens) throw ForbiddenException(WriteAccess.reviewWidensProposerMessage(tree.id.value))
        }
    }

    /**
     * FT-014: the write's change. In [WriteMode.PROPOSE], when a check already marked the
     * write as pending, nothing is changed and [draft] is stored as a proposal; otherwise
     * [change] runs inside a savepoint, and if it then needs review ([ProposalNeeded] from
     * [requireNoGainedReach]) it is rolled back to the savepoint (row changes and the graph
     * generation advance alike) and stored as a proposal. [draft] is built only then. In
     * the other modes [change] simply runs.
     */
    fun <T> tentatively(draft: () -> ProposalDraft, change: () -> T): WriteOutcome<T> {
        pending?.let { return WriteOutcome.Proposed(propose(draft(), it)) }
        if (mode != WriteMode.PROPOSE) return WriteOutcome.Applied(change())
        val savepoint = withJdbc { it.setSavepoint() }
        return try {
            val value = change()
            withJdbc { it.releaseSavepoint(savepoint) }
            WriteOutcome.Applied(value)
        } catch (needed: ProposalNeeded) {
            withJdbc { it.rollback(savepoint) }
            visibleAfter = null
            WriteOutcome.Proposed(propose(draft(), needed.reason))
        }
    }

    /** Stores [draft] as a pending proposal by this caller and audits it, in this transaction. */
    private fun propose(draft: ProposalDraft, reason: ProposalReason): ChangeProposalDto {
        check(mode == WriteMode.PROPOSE && subjects.isNotEmpty())
        val record = ChangeProposalRepository.insert(
            treeId = treeUuid,
            proposer = Uuid.parse(caller.value),
            operation = draft.operation,
            targetId = draft.targetId,
            baseVersion = draft.baseVersion,
            payload = draft.payload,
            subjects = subjects.toList(),
            reason = reason,
        )
        AuditLogRepository.append(
            treeId = treeUuid,
            actorAccountId = Uuid.parse(caller.value),
            entityType = CHANGE_PROPOSAL_ENTITY,
            entityId = record.id.toString(),
            action = AuditAction.CREATE,
            beforeJson = null,
            afterJson = AuditJson.encodeToString(ChangeProposalAuditDto.serializer(), record.toAuditDto()),
        )
        return ChangeProposalViews.forProposer(record)
    }

    private fun decide(people: Collection<Uuid>, access: EditAccess) {
        subjects += people
        when (mode) {
            WriteMode.REVIEW -> requireReviewable(people)
            WriteMode.PROPOSE, WriteMode.DIRECT -> when (access) {
                EditAccess.LIVE -> Unit
                EditAccess.OUTSIDE_BRANCH ->
                    if (mode == WriteMode.PROPOSE) {
                        markPending(ProposalReason.OUTSIDE_BRANCH)
                    } else {
                        throw ForbiddenException(WriteAccess.outsideBranchMessage(tree.id.value))
                    }
                EditAccess.NONE -> throw ForbiddenException(WriteAccess.noEditAccessMessage(tree.id.value))
            }
        }
    }

    private fun markPending(reason: ProposalReason) {
        val current = pending
        if (current == null || reason.ordinal > current.ordinal) pending = reason
    }
}

private fun Uuid.toPersonId() = PersonId(toString())

private fun Set<Uuid>.toPersonIds(): Set<PersonId> = mapTo(mutableSetOf()) { it.toPersonId() }
