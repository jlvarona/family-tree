package com.moxietech.family_tree.format

import com.moxietech.family_tree.domain.GedcomDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month

/**
 * Parses the free-text genealogical dates researchers actually have —
 * "abt 1890", "bef 12 Mar 1901", "bet 1880 and 1885" — into a [GedcomDate]
 * with a sortable range. Month names accepted in English and Spanish
 * abbreviations (GEDCOM convention + MVP bilingual UI).
 *
 * Deliberately not a full GEDCOM 7 date grammar — covers the qualifiers the
 * MVP exposes (about/before/after/between) plus year/month/year+month/full
 * dates. Anything else is kept as display text with no sortable range,
 * never thrown as an error: a date a user typed should always be savable.
 */
object GedcomDateParser {

    private val monthsByAbbreviation: Map<String, Month> = buildMap {
        put("jan", Month.JANUARY); put("ene", Month.JANUARY)
        put("feb", Month.FEBRUARY)
        put("mar", Month.MARCH)
        put("apr", Month.APRIL); put("abr", Month.APRIL)
        put("may", Month.MAY); put("mai", Month.MAY)
        put("jun", Month.JUNE)
        put("jul", Month.JULY)
        put("aug", Month.AUGUST); put("ago", Month.AUGUST)
        put("sep", Month.SEPTEMBER); put("set", Month.SEPTEMBER)
        put("oct", Month.OCTOBER)
        put("nov", Month.NOVEMBER)
        put("dec", Month.DECEMBER); put("dic", Month.DECEMBER)
    }

    fun parse(raw: String): GedcomDate {
        val text = raw.trim()
        if (text.isEmpty()) return GedcomDate.UNKNOWN

        val lower = text.lowercase()
        val (earliest, latest) = when {
            lower.startsWith("bet ") || lower.startsWith("between ") -> parseBetween(text)
            lower.startsWith("abt ") || lower.startsWith("about ") ||
                lower.startsWith("c.") || lower.startsWith("circa ") -> parseApproximate(stripQualifier(text))
            lower.startsWith("bef ") || lower.startsWith("before ") -> null to parseSingle(stripQualifier(text))?.second
            lower.startsWith("aft ") || lower.startsWith("after ") -> parseSingle(stripQualifier(text))?.first to null
            else -> parseSingle(text)?.let { it.first to it.second } ?: (null to null)
        }
        return GedcomDate(displayText = text, earliest = earliest, latest = latest)
    }

    private fun stripQualifier(text: String): String =
        text.substringAfter(' ', text).trim().ifEmpty { text }

    private fun parseBetween(text: String): Pair<LocalDate?, LocalDate?> {
        val body = stripQualifier(text)
        val parts = Regex("(?i)\\s+and\\s+").split(body, limit = 2)
        if (parts.size != 2) return null to null
        val start = parseSingle(parts[0].trim())
        val end = parseSingle(parts[1].trim())
        return start?.first to end?.second
    }

    private fun parseApproximate(text: String): Pair<LocalDate?, LocalDate?> {
        val bounds = parseSingle(text) ?: return null to null
        // "about" widens the window: treat as +/- 2 years of uncertainty around
        // whatever precision was given, rather than pretending to day-level accuracy.
        val earliestYear = bounds.first.year - 2
        val latestYear = bounds.second.year + 2
        return LocalDate(earliestYear, 1, 1) to LocalDate(latestYear, 12, 31)
    }

    /** Resolves one date token to its (earliest, latest) bound, at whatever precision it was given. */
    private fun parseSingle(token: String): Pair<LocalDate, LocalDate>? {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return null

        // ISO: 1901-03-12
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(trimmed)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { LocalDate(y.toInt(), mo.toInt(), d.toInt()) }
                .getOrNull()?.let { it to it }
        }

        // "12 Mar 1901" / "Mar 1901" / "March 1901" / "1901"
        val fields = trimmed.split(Regex("\\s+"))
        return when (fields.size) {
            1 -> yearOnly(fields[0])
            2 -> monthYear(fields[0], fields[1])
            3 -> dayMonthYear(fields[0], fields[1], fields[2])
            else -> null
        }
    }

    private fun yearOnly(yearText: String): Pair<LocalDate, LocalDate>? {
        val year = yearText.toIntOrNull() ?: return null
        return LocalDate(year, 1, 1) to LocalDate(year, 12, 31)
    }

    private fun monthYear(monthText: String, yearText: String): Pair<LocalDate, LocalDate>? {
        val month = monthOf(monthText) ?: return null
        val year = yearText.toIntOrNull() ?: return null
        val start = LocalDate(year, month, 1)
        val end = LocalDate(year, month, daysInMonth(year, month))
        return start to end
    }

    private fun dayMonthYear(dayText: String, monthText: String, yearText: String): Pair<LocalDate, LocalDate>? {
        val day = dayText.toIntOrNull() ?: return null
        val month = monthOf(monthText) ?: return null
        val year = yearText.toIntOrNull() ?: return null
        val date = runCatching { LocalDate(year, month, day) }.getOrNull() ?: return null
        return date to date
    }

    private fun monthOf(text: String): Month? {
        val key = text.lowercase().take(3)
        return monthsByAbbreviation[key]
    }

    private fun daysInMonth(year: Int, month: Month): Int {
        val isLeap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        return when (month) {
            Month.JANUARY, Month.MARCH, Month.MAY, Month.JULY,
            Month.AUGUST, Month.OCTOBER, Month.DECEMBER -> 31
            Month.APRIL, Month.JUNE, Month.SEPTEMBER, Month.NOVEMBER -> 30
            Month.FEBRUARY -> if (isLeap) 29 else 28
        }
    }
}
