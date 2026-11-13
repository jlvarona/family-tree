package com.moxietech.family_tree.domain

import kotlinx.datetime.LocalDate

/**
 * A genealogical date: the free-text form a researcher actually knows
 * ("abt 1890", "bet 1880 and 1885", "12 Mar 1901") plus a sortable range
 * derived from it so events and timelines can still be ordered.
 *
 * Never construct this directly from user input — use
 * [com.moxietech.family_tree.format.GedcomDateParser.parse], which owns the
 * rules for turning free text into [earliest]/[latest].
 */
data class GedcomDate(
    val displayText: String,
    val earliest: LocalDate?,
    val latest: LocalDate?,
) {
    val isApproximate: Boolean get() = earliest != latest

    companion object {
        /** A date with no textual content; distinct from "unparseable text". */
        val UNKNOWN = GedcomDate(displayText = "", earliest = null, latest = null)
    }
}
