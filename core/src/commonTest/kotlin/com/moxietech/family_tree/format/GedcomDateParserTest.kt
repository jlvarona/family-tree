package com.moxietech.family_tree.format

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GedcomDateParserTest {

    @Test
    fun `empty text is unknown`() {
        val d = GedcomDateParser.parse("   ")
        assertEquals("", d.displayText)
        assertNull(d.earliest)
        assertNull(d.latest)
    }

    @Test
    fun `exact day month year`() {
        val d = GedcomDateParser.parse("12 Mar 1901")
        assertEquals(LocalDate(1901, 3, 12), d.earliest)
        assertEquals(LocalDate(1901, 3, 12), d.latest)
        assertEquals(false, d.isApproximate)
    }

    @Test
    fun `iso date`() {
        val d = GedcomDateParser.parse("1901-03-12")
        assertEquals(LocalDate(1901, 3, 12), d.earliest)
        assertEquals(LocalDate(1901, 3, 12), d.latest)
    }

    @Test
    fun `month and year spans the whole month`() {
        val d = GedcomDateParser.parse("Mar 1901")
        assertEquals(LocalDate(1901, 3, 1), d.earliest)
        assertEquals(LocalDate(1901, 3, 31), d.latest)
    }

    @Test
    fun `spanish month abbreviation`() {
        val d = GedcomDateParser.parse("12 Ene 1901")
        assertEquals(LocalDate(1901, 1, 12), d.earliest)
    }

    @Test
    fun `year only spans the whole year`() {
        val d = GedcomDateParser.parse("1890")
        assertEquals(LocalDate(1890, 1, 1), d.earliest)
        assertEquals(LocalDate(1890, 12, 31), d.latest)
    }

    @Test
    fun `about widens the range and is approximate`() {
        val d = GedcomDateParser.parse("abt 1890")
        assertEquals(LocalDate(1888, 1, 1), d.earliest)
        assertEquals(LocalDate(1892, 12, 31), d.latest)
        assertEquals(true, d.isApproximate)
    }

    @Test
    fun `before has no earliest bound`() {
        val d = GedcomDateParser.parse("bef 1900")
        assertNull(d.earliest)
        assertEquals(LocalDate(1900, 12, 31), d.latest)
    }

    @Test
    fun `after has no latest bound`() {
        val d = GedcomDateParser.parse("aft 1850")
        assertEquals(LocalDate(1850, 1, 1), d.earliest)
        assertNull(d.latest)
    }

    @Test
    fun `between two years`() {
        val d = GedcomDateParser.parse("bet 1880 and 1885")
        assertEquals(LocalDate(1880, 1, 1), d.earliest)
        assertEquals(LocalDate(1885, 12, 31), d.latest)
    }

    @Test
    fun `unparseable text is kept as display text with no range`() {
        val d = GedcomDateParser.parse("sometime last century")
        assertEquals("sometime last century", d.displayText)
        assertNull(d.earliest)
        assertNull(d.latest)
    }
}
