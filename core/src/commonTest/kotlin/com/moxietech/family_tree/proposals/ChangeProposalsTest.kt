package com.moxietech.family_tree.proposals

import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.GrantRole
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.permissions.EditAuthority
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangeProposalsTest {

    private fun ids(vararg labels: String) = labels.map(::PersonId)

    private fun grant(role: GrantRole, direction: BranchDirection, vararg scope: String) = EditAuthority.ScopedGrant(
        role = role,
        branch = Branch(PersonId(scope.first()), direction, depth = null, includePartners = true),
        scope = scope.mapTo(mutableSetOf(), ::PersonId),
    )

    @Test
    fun theOwnerReviewsEverything() {
        val owner = ReviewAuthority.owner()
        assertTrue(owner.isReviewer)
        assertTrue(owner.canReview(ids("a", "z")))
    }

    @Test
    fun aBranchOwnerReviewsOnlyWhenEverySubjectIsInsideAnOwnedBranch() {
        val reviewer = ReviewAuthority.member(
            listOf(
                grant(GrantRole.BRANCH_OWNER, BranchDirection.DESCENDANTS, "aunt", "uncle", "cousin"),
                grant(GrantRole.BRANCH_OWNER, BranchDirection.DESCENDANTS, "mum", "me"),
            ),
        )
        assertTrue(reviewer.isReviewer)
        assertTrue(reviewer.canReview(ids("cousin")))
        // Subjects across two owned branches: both covered.
        assertTrue(reviewer.canReview(ids("cousin", "me")))
        // A cross-branch change with one subject outside: only the tree owner.
        assertFalse(reviewer.canReview(ids("cousin", "out1")))
        assertFalse(reviewer.canReview(emptyList()))
    }

    @Test
    fun contributorsViewersAndAdminsDontReviewByThatRole() {
        for (role in listOf(GrantRole.CONTRIBUTOR, GrantRole.VIEWER)) {
            val member = ReviewAuthority.member(listOf(grant(role, BranchDirection.DESCENDANTS, "aunt", "cousin")))
            assertFalse(member.isReviewer, "$role")
            assertFalse(member.canReview(ids("cousin")), "$role")
        }
        val admin = ReviewAuthority.member(listOf(grant(GrantRole.ADMIN, BranchDirection.WHOLE_TREE, "gp1", "cousin")))
        assertFalse(admin.canReview(ids("cousin")))
        // A BRANCH_OWNER grant stored with an ADMIN role on a limited branch carries nothing either.
        val limitedAdmin = ReviewAuthority.member(listOf(grant(GrantRole.ADMIN, BranchDirection.SIDE, "gp1", "cousin")))
        assertFalse(limitedAdmin.isReviewer)
    }

    @Test
    fun approvedFieldsMustBelongToTheOperation() {
        assertEquals(ProposalOperation.UPDATE_UNION.fields.toSet(), ProposalFields.approved(ProposalOperation.UPDATE_UNION, null).fields)
        assertEquals(
            setOf(ProposalField.UNION_TYPE),
            ProposalFields.approved(ProposalOperation.UPDATE_UNION, listOf("UNION_TYPE")).fields,
        )
        assertEquals(listOf(ProposalFields.NO_FIELDS_MESSAGE), ProposalFields.approved(ProposalOperation.UPDATE_UNION, emptyList()).errors)
        assertEquals(
            listOf(ProposalFields.unknownFieldMessage("BIO"), ProposalFields.unknownFieldMessage("nope")),
            ProposalFields.approved(ProposalOperation.UPDATE_UNION, listOf("BIO", "nope")).errors,
        )
        // Partners are one value: neither position can be approved alone.
        assertEquals(1, ProposalFields.approved(ProposalOperation.UPDATE_UNION, listOf("partner1Id")).errors.size)
        assertEquals(listOf(ProposalField.RECORD), ProposalOperation.ADD_CHILD.fields)
        assertFalse(ProposalOperation.ADD_CHILD.isPerField)
        assertTrue(ProposalOperation.UPDATE_PERSON.isPerField)
        assertTrue(ProposalOperation.DELETE_EVENT.hasBaseVersion)
        assertFalse(ProposalOperation.CREATE_CITATION.hasBaseVersion)
    }

    @Test
    fun composeTakesApprovedGroupsWholeAndTheRestAsStored() {
        val proposed = JsonObject(
            mapOf(
                "partner1Id" to JsonPrimitive("x"),
                "partner2Id" to JsonPrimitive("y"),
                "type" to JsonPrimitive("DIVORCED"),
                "startDate" to JsonPrimitive("1980"),
            ),
        )
        val current = JsonObject(
            mapOf(
                "partner1Id" to JsonPrimitive("a"),
                "partner2Id" to JsonPrimitive("b"),
                "type" to JsonPrimitive("MARRIED"),
                "startDate" to JsonPrimitive("1975"),
                "endDate" to JsonPrimitive("1990"),
            ),
        )
        val datesOnly = ProposalFields.compose(ProposalOperation.UPDATE_UNION, setOf(ProposalField.UNION_DATES), proposed, current)
        assertEquals(JsonPrimitive("a"), datesOnly["partner1Id"])
        assertEquals(JsonPrimitive("MARRIED"), datesOnly["type"])
        assertEquals(JsonPrimitive("1980"), datesOnly["startDate"])
        // A key the proposer left out is null in their proposal, and the group goes together.
        assertEquals(JsonNull, datesOnly["endDate"])
        val partners = ProposalFields.compose(ProposalOperation.UPDATE_UNION, setOf(ProposalField.PARTNERS), proposed, current)
        assertEquals(JsonPrimitive("x"), partners["partner1Id"])
        assertEquals(JsonPrimitive("y"), partners["partner2Id"])
        assertEquals(JsonPrimitive("1975"), partners["startDate"])
        assertFailsWith<IllegalArgumentException> {
            ProposalFields.compose(ProposalOperation.ADD_CHILD, setOf(ProposalField.RECORD), proposed, current)
        }
        assertTrue(ProposalFields.differs(ProposalField.UNION_DATES, proposed, current))
        assertTrue(ProposalFields.differs(ProposalField.UNION_TYPE, proposed, current))
        assertFalse(ProposalFields.differs(ProposalField.UNION_TYPE, proposed, JsonObject(mapOf("type" to JsonPrimitive("DIVORCED")))))
        assertEquals(setOf("startDate", "endDate"), ProposalFields.slice(ProposalField.UNION_DATES, proposed).keys)
    }
}
