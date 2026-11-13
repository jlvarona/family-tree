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
    fun `confidence must be 0 to 3`() {
        assertTrue(Validation.validateConfidence(4).isNotEmpty())
        assertTrue(Validation.validateConfidence(-1).isNotEmpty())
        assertEquals(emptyList(), Validation.validateConfidence(2))
    }
}
