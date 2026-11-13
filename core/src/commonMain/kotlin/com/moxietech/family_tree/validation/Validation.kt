package com.moxietech.family_tree.validation

import com.moxietech.family_tree.domain.GedcomDate

/**
 * Field-level validation shared by server request handling and client forms,
 * so both sides reject the same input the same way. Returns the list of
 * problems found (empty = valid) rather than throwing, so callers can show
 * every field error at once instead of one at a time.
 */
object Validation {

    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")

    fun validateNotBlank(fieldLabel: String, value: String): List<String> =
        if (value.isBlank()) listOf("$fieldLabel must not be blank") else emptyList()

    fun validatePersonName(givenNames: String): List<String> = validateNotBlank("Given names", givenNames)

    fun validateTreeName(name: String): List<String> = validateNotBlank("Tree name", name)

    fun validateEmail(email: String): List<String> =
        if (!EMAIL_PATTERN.matches(email)) listOf("'$email' is not a valid email address") else emptyList()

    fun validateDateOrder(start: GedcomDate?, end: GedcomDate?): List<String> {
        val startBound = start?.latest
        val endBound = end?.earliest
        if (startBound != null && endBound != null && startBound > endBound) {
            return listOf("Start date must not be after end date")
        }
        return emptyList()
    }

    fun validateConfidence(level: Int): List<String> =
        if (level !in 0..3) listOf("Confidence must be between 0 and 3") else emptyList()
}
