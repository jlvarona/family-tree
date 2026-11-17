package com.moxietech.family_tree.merge

import com.moxietech.family_tree.api.UnionChildDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.domain.Sex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersonMergeTest {

    private val survivor = MergeProfile("Juan", "Pérez", "García", Sex.MALE, isLiving = false, bio = "Farmer")
    private val retired = MergeProfile("Juan José", "Pérez", null, Sex.UNKNOWN, isLiving = true, bio = "  ")

    @Test
    fun everyDifferenceIsListedIncludingBlankAgainstAValueAndLivingStatus() {
        assertEquals(
            listOf(MergeField.GIVEN_NAMES, MergeField.SURNAME_MATERNAL, MergeField.SEX, MergeField.IS_LIVING, MergeField.BIO),
            PersonMerge.differingFields(survivor, retired).toList(),
        )
    }

    @Test
    fun nullAndBlankOptionalTextAreTheSameValue() {
        val a = survivor.copy(surnameMaternal = null, bio = "")
        val b = survivor.copy(surnameMaternal = "  ", bio = null)
        assertEquals(emptySet(), PersonMerge.differingFields(a, b))
        // Given names are required and compared exactly.
        assertEquals(setOf(MergeField.GIVEN_NAMES), PersonMerge.differingFields(a, a.copy(givenNames = "Juan ")))
    }

    @Test
    fun choicesMustCoverExactlyTheDifferingFields() {
        val differing = PersonMerge.differingFields(survivor, retired)
        val all = differing.associateWith { MergeSide.SURVIVOR }
        assertEquals(emptyList(), PersonMerge.choiceErrors(differing, all))
        assertEquals(1, PersonMerge.choiceErrors(differing, all - MergeField.BIO).size, "a missing choice")
        assertEquals(1, PersonMerge.choiceErrors(differing, all + (MergeField.SURNAME_PATERNAL to MergeSide.RETIRED)).size, "a choice for an equal field")
    }

    @Test
    fun mergedKeepsChosenValuesExactlyAndEqualFieldsAsTheyAre() {
        val choices = mapOf(
            MergeField.GIVEN_NAMES to MergeSide.RETIRED,
            MergeField.SURNAME_MATERNAL to MergeSide.RETIRED,
            MergeField.SEX to MergeSide.SURVIVOR,
            MergeField.IS_LIVING to MergeSide.RETIRED,
            MergeField.BIO to MergeSide.SURVIVOR,
        )
        val merged = PersonMerge.merged(survivor, retired, choices)
        assertEquals(MergeProfile("Juan José", "Pérez", null, Sex.MALE, isLiving = true, bio = "Farmer"), merged)
    }

    @Test
    fun profilePhotosAreAnExplicitChoiceIncludingAPhotoAgainstNone() {
        val photo = "0b8f6c5e-3f2a-4c1d-9e7b-1a2b3c4d5e6f"
        val other = "7d1e2f3a-4b5c-4d6e-8f9a-0b1c2d3e4f5a"
        val a = survivor.copy(profileMediaId = photo)
        assertEquals(emptySet(), PersonMerge.differingFields(a, a.copy(profileMediaId = photo.uppercase())), "same media in another case")
        assertEquals(setOf(MergeField.PROFILE_PHOTO), PersonMerge.differingFields(a, a.copy(profileMediaId = other)))
        assertEquals(setOf(MergeField.PROFILE_PHOTO), PersonMerge.differingFields(a, a.copy(profileMediaId = null)), "a photo against none")
        assertEquals(1, PersonMerge.choiceErrors(setOf(MergeField.PROFILE_PHOTO), emptyMap()).size, "never kept or dropped silently")

        val withRetired = PersonMerge.merged(a, a.copy(profileMediaId = other), mapOf(MergeField.PROFILE_PHOTO to MergeSide.RETIRED))
        assertEquals(other, withRetired.profileMediaId)
        val noPhoto = PersonMerge.merged(a, a.copy(profileMediaId = null), mapOf(MergeField.PROFILE_PHOTO to MergeSide.RETIRED))
        assertEquals(null, noPhoto.profileMediaId, "choosing the record without a photo is deliberate")
    }

    @Test
    fun mergeRefusesIncompleteChoices() {
        assertFailsWith<IllegalArgumentException> { PersonMerge.merged(survivor, retired, emptyMap()) }
        assertEquals(survivor, PersonMerge.merged(survivor, survivor, emptyMap()))
    }

    @Test
    fun partnersOfEachOtherConflict() {
        val unions = listOf(union("u", "s", "r"))
        assertEquals(setOf(MergeConflict.PARTNERS_OF_EACH_OTHER), PersonMerge.relationshipConflicts("s", "r", unions))
    }

    @Test
    fun parentAndChildConflictInEitherDirection() {
        assertEquals(setOf(MergeConflict.PARENT_AND_CHILD), PersonMerge.relationshipConflicts("s", "r", listOf(union("u", "s", "x", "r"))))
        assertEquals(setOf(MergeConflict.PARENT_AND_CHILD), PersonMerge.relationshipConflicts("s", "r", listOf(union("u", "x", "r", "s"))))
    }

    @Test
    fun distantAncestorConflictsInEitherDirection() {
        val unions = listOf(union("u1", "s", null, "a"), union("u2", "a", "b", "c"), union("u3", "c", null, "r"))
        assertEquals(setOf(MergeConflict.ANCESTOR_AND_DESCENDANT), PersonMerge.relationshipConflicts("s", "r", unions))
        assertEquals(setOf(MergeConflict.ANCESTOR_AND_DESCENDANT), PersonMerge.relationshipConflicts("r", "s", unions))
    }

    @Test
    fun siblingsWithTheSameRelationCanMergeButDifferentRelationsConflict() {
        assertEquals(emptySet(), PersonMerge.relationshipConflicts("s", "r", listOf(union("u", "m", "d", "s", "r"))))
        val mixed = union("u", "m", "d").copy(children = listOf(UnionChildDto("s", "BIRTH"), UnionChildDto("r", "ADOPTED")))
        assertEquals(setOf(MergeConflict.CHILD_LINK_RELATION_DIFFERS), PersonMerge.relationshipConflicts("s", "r", listOf(mixed)))
    }

    @Test
    fun unrelatedFamiliesAndPreexistingLoopsElsewhereDontConflict() {
        val unions = listOf(
            union("u1", "m", "d", "s"),
            union("u2", "p", "q", "r"),
            union("u3", "s", "x"),
            union("u4", "r", "x"),
            // A loop between other people only.
            union("loop1", "a", null, "b"),
            union("loop2", "b", null, "a"),
        )
        assertTrue(PersonMerge.relationshipConflicts("s", "r", unions).isEmpty())
    }

    private fun union(id: String, partner1: String, partner2: String?, vararg children: String) = UnionDto(
        id = id,
        treeId = "t",
        partner1Id = partner1,
        partner2Id = partner2,
        type = "MARRIED",
        startDate = null,
        endDate = null,
        children = children.map { UnionChildDto(it, "BIRTH") },
        version = 1,
    )
}
