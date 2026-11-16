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
    fun `reversed between keeps display text without bounds`() {
        val d = GedcomDateParser.parse("bet 1885 and 1880")
        assertEquals("bet 1885 and 1880", d.displayText)
        assertNull(d.earliest)
        assertNull(d.latest)
    }

    @Test
    fun `between within one year is kept`() {
        val d = GedcomDateParser.parse("bet Mar 1880 and Mar 1880")
        assertEquals(LocalDate(1880, 3, 1), d.earliest)
        assertEquals(LocalDate(1880, 3, 31), d.latest)
    }

    @Test
    fun `spanish months in a between range`() {
        val b = GedcomDateParser.parse("bet 3 Ago 1880 and 5 Dic 1881")
        assertEquals(LocalDate(1880, 8, 3), b.earliest)
        assertEquals(LocalDate(1881, 12, 5), b.latest)
    }

    @Test
    fun `signed and extended years inside the storable range get bounds`() {
        val bc = GedcomDateParser.parse("-500")
        assertEquals(LocalDate(-500, 1, 1), bc.earliest)
        assertEquals(LocalDate(-500, 12, 31), bc.latest)
        val far = GedcomDateParser.parse("12345")
        assertEquals(LocalDate(12345, 1, 1), far.earliest)
        val firstFullYear = GedcomDateParser.parse("-4712")
        assertEquals(LocalDate(-4712, 1, 1), firstFullYear.earliest)
    }

    @Test
    fun `earliest storable date is 24 Nov -4713`() {
        val first = GedcomDateParser.parse("24 Nov -4713")
        assertEquals(LocalDate(-4713, 11, 24), first.earliest)
        assertEquals(LocalDate(-4713, 11, 24), first.latest)
        // One day earlier, or a month/year whose start falls before it, is text-only.
        for (input in listOf("23 Nov -4713", "Nov -4713", "-4713")) {
            assertTextOnly(input)
        }
    }

    @Test
    fun `latest storable date is 31 Dec 5874897`() {
        val last = GedcomDateParser.parse("31 Dec 5874897")
        assertEquals(LocalDate(5_874_897, 12, 31), last.earliest)
        assertEquals(LocalDate(5_874_897, 12, 31), last.latest)
        val lastYear = GedcomDateParser.parse("5874897")
        assertEquals(LocalDate(5_874_897, 1, 1), lastYear.earliest)
        assertEquals(LocalDate(5_874_897, 12, 31), lastYear.latest)
        for (input in listOf("1 Jan 5874898", "5874898")) {
            assertTextOnly(input)
        }
    }

    @Test
    fun `between with an invalid endpoint keeps text without a partial range`() {
        for (input in listOf(
            "bet 1880 and 999999999",
            "bet 999999999 and 1880",
            "bet 1880 and 1000000000",
            "bet 23 Nov -4713 and 1880",
            "bet 1880 and sometime",
        )) {
            assertTextOnly(input)
        }
    }

    @Test
    fun `out of range years keep display text without bounds and never throw`() {
        val inputs = listOf(
            "999999999", // representable by kotlinx-datetime, beyond storage
            "1000000000", // beyond kotlinx-datetime itself
            "${Int.MAX_VALUE}",
            "${Int.MIN_VALUE}",
            "99999999999999999999",
            "Mar ${Int.MAX_VALUE}",
            "12 Mar ${Int.MIN_VALUE}",
            "bef ${Int.MAX_VALUE}",
            "aft ${Int.MIN_VALUE}",
            "bet ${Int.MIN_VALUE} and ${Int.MAX_VALUE}",
            "1901-13-01",
            "1901-02-30",
        )
        for (input in inputs) {
            assertTextOnly(input)
        }
    }

    @Test
    fun `approximate arithmetic is overflow safe`() {
        for (input in listOf(
            "abt ${Int.MAX_VALUE}",
            "abt ${Int.MIN_VALUE}",
            "abt ${Int.MAX_VALUE - 1}",
            "abt 999999999",
            "abt -999999999",
        )) {
            assertTextOnly(input)
        }
    }

    @Test
    fun `approximate dates near the storable limits drop bounds instead of leaving the range`() {
        // -4710 widens to 1 Jan -4712, inside; -4711 widens to 1 Jan -4713, before 24 Nov -4713.
        assertEquals(LocalDate(-4712, 1, 1), GedcomDateParser.parse("abt -4710").earliest)
        assertTextOnly("abt -4711")
        // 5874895 widens to 31 Dec 5874897, the last storable day; 5874896 goes past it.
        assertEquals(LocalDate(5_874_897, 12, 31), GedcomDateParser.parse("abt 5874895").latest)
        assertTextOnly("abt 5874896")
    }

    private fun assertTextOnly(input: String) {
        val d = GedcomDateParser.parse(input)
        assertEquals(input, d.displayText, input)
        assertNull(d.earliest, input)
        assertNull(d.latest, input)
    }

    @Test
    fun `unparseable text is kept as display text with no range`() {
        val d = GedcomDateParser.parse("sometime last century")
        assertEquals("sometime last century", d.displayText)
        assertNull(d.earliest)
        assertNull(d.latest)
    }
}
