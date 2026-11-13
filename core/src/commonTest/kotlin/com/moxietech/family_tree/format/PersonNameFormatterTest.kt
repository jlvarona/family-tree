package com.moxietech.family_tree.format

import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.MediaId
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.TreeId
import kotlin.test.Test
import kotlin.test.assertEquals

class PersonNameFormatterTest {

    private fun person(
        givenNames: String,
        surnamePaternal: String?,
        surnameMaternal: String?,
    ) = Person(
        id = PersonId("p1"),
        treeId = TreeId("t1"),
        givenNames = givenNames,
        surnamePaternal = surnamePaternal,
        surnameMaternal = surnameMaternal,
        sex = Sex.UNKNOWN,
        isLiving = true,
        profileMediaId = null as MediaId?,
        bio = null,
        createdByAccountId = null as AccountId?,
        version = 1,
    )

    @Test
    fun `two Spanish surnames format in order`() {
        val p = person("Juan", "Pérez", "García")
        assertEquals("Juan Pérez García", PersonNameFormatter.fullName(p))
        assertEquals("Pérez García", PersonNameFormatter.surnames(p))
        assertEquals("Pérez García, Juan", PersonNameFormatter.sortKey(p))
    }

    @Test
    fun `single surname formats without trailing space`() {
        val p = person("Jane", "Smith", null)
        assertEquals("Jane Smith", PersonNameFormatter.fullName(p))
        assertEquals("Smith, Jane", PersonNameFormatter.sortKey(p))
    }

    @Test
    fun `no surname on record formats as given names only`() {
        val p = person("Jane", null, null)
        assertEquals("Jane", PersonNameFormatter.fullName(p))
        assertEquals("Jane", PersonNameFormatter.sortKey(p))
    }

    @Test
    fun `blank surname fields are treated as absent`() {
        val p = person("Jane", "  ", "")
        assertEquals("Jane", PersonNameFormatter.fullName(p))
    }
}
