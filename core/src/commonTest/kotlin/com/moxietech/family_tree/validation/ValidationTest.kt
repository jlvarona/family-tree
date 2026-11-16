package com.moxietech.family_tree.validation

import com.moxietech.family_tree.format.GedcomDateParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationTest {

    @Test
    fun `blank person name is invalid`() {
        assertTrue(Validation.validatePersonName("  ").isNotEmpty())
        assertEquals(emptyList(), Validation.validatePersonName("Juan"))
    }

    @Test
    fun `blank tree name is invalid`() {
        assertTrue(Validation.validateTreeName("").isNotEmpty())
        assertEquals(emptyList(), Validation.validateTreeName("Pérez family"))
    }

    @Test
    fun `email format is checked`() {
        assertTrue(Validation.validateEmail("not-an-email").isNotEmpty())
        assertEquals(emptyList(), Validation.validateEmail("dev@example.com"))
    }

    @Test
    fun `start date after end date is invalid`() {
        val start = GedcomDateParser.parse("1900")
        val end = GedcomDateParser.parse("1890")
        assertTrue(Validation.validateDateOrder(start, end).isNotEmpty())
        assertEquals(emptyList(), Validation.validateDateOrder(end, start))
    }

    @Test
    fun `two dates in the same year are allowed in either order`() {
        val a = GedcomDateParser.parse("1900")
        val b = GedcomDateParser.parse("Mar 1900")
        assertEquals(emptyList(), Validation.validateDateOrder(a, a))
        assertEquals(emptyList(), Validation.validateDateOrder(a, b))
        assertEquals(emptyList(), Validation.validateDateOrder(b, a))
    }

    @Test
    fun `overlapping uncertain bounds are allowed`() {
        val start = GedcomDateParser.parse("abt 1890")
        val end = GedcomDateParser.parse("1889")
        assertEquals(emptyList(), Validation.validateDateOrder(start, end))
        val between = GedcomDateParser.parse("bet 1880 and 1885")
        assertEquals(emptyList(), Validation.validateDateOrder(between, GedcomDateParser.parse("1881")))
    }

    @Test
    fun `clearly reversed bounds are rejected`() {
        val start = GedcomDateParser.parse("12 Mar 1901")
        val end = GedcomDateParser.parse("11 Mar 1901")
        assertTrue(Validation.validateDateOrder(start, end).isNotEmpty())
        assertTrue(
            Validation.validateDateOrder(GedcomDateParser.parse("abt 1900"), GedcomDateParser.parse("1897")).isNotEmpty(),
        )
    }

    @Test
    fun `open ended and unparsed dates are allowed`() {
        // "bef" has no earliest start; "aft" has no latest end: neither can prove a reversal.
        val startBefore = GedcomDateParser.parse("bef 1950")
        val endAfter = GedcomDateParser.parse("aft 1900")
        assertEquals(emptyList(), Validation.validateDateOrder(startBefore, GedcomDateParser.parse("1900")))
        assertEquals(emptyList(), Validation.validateDateOrder(GedcomDateParser.parse("1950"), endAfter))
        assertEquals(emptyList(), Validation.validateDateOrder(GedcomDateParser.parse("bet 1885 and 1880"), GedcomDateParser.parse("1700")))
        assertEquals(emptyList(), Validation.validateDateOrder(null, GedcomDateParser.parse("1700")))
        // Known bounds on both sides still apply: after 1950 cannot precede before 1900.
        assertTrue(Validation.validateDateOrder(GedcomDateParser.parse("aft 1950"), GedcomDateParser.parse("bef 1900")).isNotEmpty())
    }

    @Test
    fun `confidence must be 0 to 3`() {
        assertTrue(Validation.validateConfidence(4).isNotEmpty())
        assertTrue(Validation.validateConfidence(-1).isNotEmpty())
        assertEquals(emptyList(), Validation.validateConfidence(2))
    }
}
