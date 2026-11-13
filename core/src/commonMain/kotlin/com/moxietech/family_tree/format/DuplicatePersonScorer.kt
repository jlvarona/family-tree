package com.moxietech.family_tree.format

import com.moxietech.family_tree.domain.GedcomDate
import com.moxietech.family_tree.domain.Person
import kotlin.math.max
import kotlin.math.min

/**
 * Fuzzy-matches a candidate new person against an existing one on name,
 * birth date and parents, so the "is this person already in the tree?"
 * prompt (concept-architecture.md MVP feature) can warn before a duplicate
 * is created. Returns 0 (no resemblance) .. 100 (near-certain duplicate).
 */
object DuplicatePersonScorer {

    /** Candidates scoring at or above this are shown as a "might be a duplicate" warning. */
    const val WARNING_THRESHOLD = 60

    fun score(
        candidateGivenNames: String,
        candidateSurnamePaternal: String?,
        candidateSurnameMaternal: String?,
        candidateBirthDate: GedcomDate?,
        candidateParentIds: Set<String>,
        existing: Person,
        existingBirthDate: GedcomDate?,
        existingParentIds: Set<String>,
    ): Int {
        val nameScore = nameSimilarity(
            PersonNameFormatter.fullName(candidateGivenNames, candidateSurnamePaternal, candidateSurnameMaternal),
            PersonNameFormatter.fullName(existing),
        )
        val dateScore = dateProximity(candidateBirthDate, existingBirthDate)
        val parentScore = if (candidateParentIds.isEmpty() || existingParentIds.isEmpty()) {
            null
        } else {
            if (candidateParentIds.intersect(existingParentIds).isNotEmpty()) 100 else 0
        }

        // Weighted average over whichever signals are actually available; a person
        // with no recorded date or parents yet is scored on name alone.
        val weighted = buildList {
            add(nameScore to 0.5)
            dateScore?.let { add(it to 0.3) }
            parentScore?.let { add(it to 0.2) }
        }
        val totalWeight = weighted.sumOf { it.second }
        if (totalWeight == 0.0) return 0
        return (weighted.sumOf { it.first * it.second } / totalWeight).toInt().coerceIn(0, 100)
    }

    private fun nameSimilarity(a: String, b: String): Int {
        val bigramsA = bigrams(a)
        val bigramsB = bigrams(b)
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return if (a.equals(b, ignoreCase = true)) 100 else 0
        val intersection = bigramsA.intersect(bigramsB).size
        val dice = 2.0 * intersection / (bigramsA.size + bigramsB.size)
        return (dice * 100).toInt().coerceIn(0, 100)
    }

    private fun bigrams(text: String): List<String> {
        val normalized = text.lowercase().filter { !it.isWhitespace() }
        if (normalized.length < 2) return listOf(normalized)
        return (0 until normalized.length - 1).map { normalized.substring(it, it + 2) }
    }

    /** null = no comparison possible; otherwise 0..100, falling off with years apart. */
    private fun dateProximity(a: GedcomDate?, b: GedcomDate?): Int? {
        val yearA = a?.earliest?.year ?: return null
        val yearB = b?.earliest?.year ?: return null
        val diff = max(yearA, yearB) - min(yearA, yearB)
        return when {
            diff == 0 -> 100
            diff <= 1 -> 80
            diff <= 3 -> 50
            diff <= 5 -> 20
            else -> 0
        }
    }
}
