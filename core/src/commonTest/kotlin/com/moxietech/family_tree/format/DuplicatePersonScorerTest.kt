package com.moxietech.family_tree.format

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.MediaId
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.TreeId
import kotlin.test.Test
import kotlin.test.assertTrue

class DuplicatePersonScorerTest {

    private fun existing(givenNames: String, surnamePaternal: String?) = Person(
        id = PersonId("existing"),
        treeId = TreeId("t1"),
        givenNames = givenNames,
        surnamePaternal = surnamePaternal,
        surnameMaternal = null,
        sex = Sex.UNKNOWN,
        isLiving = false,
        profileMediaId = null as MediaId?,
        bio = null,
        createdByAccountId = null as AccountId?,
        version = 1,
    )

    @Test
    fun `identical name and same birth year scores high`() {
        val score = DuplicatePersonScorer.score(
            candidateGivenNames = "Juan",
            candidateSurnamePaternal = "Perez",
            candidateSurnameMaternal = null,
            candidateBirthDate = GedcomDateParser.parse("1890"),
            candidateParentIds = emptySet(),
            existing = existing("Juan", "Perez"),
            existingBirthDate = GedcomDateParser.parse("1890"),
            existingParentIds = emptySet(),
        )
        assertTrue(score >= DuplicatePersonScorer.WARNING_THRESHOLD, "expected >= threshold, got $score")
    }

    @Test
    fun `unrelated names score low`() {
        val score = DuplicatePersonScorer.score(
            candidateGivenNames = "Zoe",
            candidateSurnamePaternal = "Nguyen",
            candidateSurnameMaternal = null,
            candidateBirthDate = GedcomDateParser.parse("1990"),
            candidateParentIds = emptySet(),
            existing = existing("Juan", "Perez"),
            existingBirthDate = GedcomDateParser.parse("1890"),
            existingParentIds = emptySet(),
        )
        assertTrue(score < DuplicatePersonScorer.WARNING_THRESHOLD, "expected < threshold, got $score")
    }

    @Test
    fun `shared parent boosts an otherwise middling name match`() {
        val withSharedParent = DuplicatePersonScorer.score(
            candidateGivenNames = "Jon",
            candidateSurnamePaternal = "Perez",
            candidateSurnameMaternal = null,
            candidateBirthDate = null,
            candidateParentIds = setOf("parentA"),
            existing = existing("Juan", "Perez"),
            existingBirthDate = null,
            existingParentIds = setOf("parentA"),
        )
        val withoutSharedParent = DuplicatePersonScorer.score(
            candidateGivenNames = "Jon",
            candidateSurnamePaternal = "Perez",
            candidateSurnameMaternal = null,
            candidateBirthDate = null,
            candidateParentIds = setOf("parentB"),
            existing = existing("Juan", "Perez"),
            existingBirthDate = null,
            existingParentIds = setOf("parentA"),
        )
        assertTrue(withSharedParent > withoutSharedParent)
    }
}
