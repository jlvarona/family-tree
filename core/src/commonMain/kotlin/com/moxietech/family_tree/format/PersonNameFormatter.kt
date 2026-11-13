package com.moxietech.family_tree.format

import com.moxietech.family_tree.domain.Person

/**
 * Formats a person's name from its three stored parts. Never concatenate
 * [Person.givenNames]/surnames by hand in UI code — go through here so
 * Spanish two-surname names ("Juan Pérez García") and single-surname names
 * ("Jane Smith") both come out right.
 */
object PersonNameFormatter {

    /** "Juan Pérez García" / "Jane Smith" / "Jane" (no surname on record). */
    fun fullName(person: Person): String = fullName(
        givenNames = person.givenNames,
        surnamePaternal = person.surnamePaternal,
        surnameMaternal = person.surnameMaternal,
    )

    fun fullName(givenNames: String, surnamePaternal: String?, surnameMaternal: String?): String {
        val surnames = listOfNotNull(
            surnamePaternal?.trim()?.takeIf { it.isNotEmpty() },
            surnameMaternal?.trim()?.takeIf { it.isNotEmpty() },
        )
        return (listOf(givenNames.trim()) + surnames).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** "Pérez García" / "Smith" / "" — surnames only, for sorting and duplicate matching. */
    fun surnames(person: Person): String = listOfNotNull(
        person.surnamePaternal?.trim()?.takeIf { it.isNotEmpty() },
        person.surnameMaternal?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" ")

    /** "García, Juan Pérez" — surname-first, for alphabetical lists. Sorts by paternal surname. */
    fun sortKey(person: Person): String {
        val surnames = surnames(person)
        return if (surnames.isEmpty()) person.givenNames.trim() else "$surnames, ${person.givenNames.trim()}"
    }
}
