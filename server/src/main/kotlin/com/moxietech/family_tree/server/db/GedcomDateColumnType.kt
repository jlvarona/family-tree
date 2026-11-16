package com.moxietech.family_tree.server.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.api.RowApi
import org.postgresql.util.PGobject
import java.time.LocalDate
import java.util.Locale

/**
 * PostgreSQL `DATE` column for GEDCOM date bounds, exact across the whole
 * range PostgreSQL stores (4714-11-24 BC to 5874897-12-31), which is the range
 * the `:core` parser keeps.
 *
 * - Exposed's javatime `date()` converts through `java.sql.Date` and the JVM
 *   time zone, whose legacy `Calendar` Julian cutover corrupts historical dates
 *   (24 Nov -4713 was stored as 4713-01-01 BC and read back as -4713-11-23).
 * - Binding `LocalDate` directly is not enough either: pgjdbc 42.7.x
 *   `TimestampUtils.toString(LocalDate)` turns anything before 4713-01-01 BC
 *   into `-infinity`.
 *
 * So writes bind a [PGobject] of type `date` holding PostgreSQL's own text
 * form ([toPostgresText]), which the server parses itself; reads use the
 * driver's typed `LocalDate` conversion, which is proleptic ISO.
 */
class GedcomDateColumnType : ColumnType<LocalDate>() {

    override fun sqlType(): String = "DATE"

    override fun valueFromDB(value: Any): LocalDate = when (value) {
        is LocalDate -> value
        is String -> parseLiteral(value)
        else -> error("Unexpected value of type ${value::class.qualifiedName} for a DATE column")
    }

    /** A `date`-typed [PGobject] with the exact PostgreSQL text, bypassing pgjdbc's `LocalDate` serializer. */
    override fun notNullValueToDB(value: LocalDate): Any = PGobject().apply {
        type = "date"
        this.value = toPostgresText(value)
    }

    override fun readObject(rs: RowApi, index: Int): Any? =
        rs.getObject(index, LocalDate::class.java, this)

    override fun nonNullValueToString(value: LocalDate): String = "'${toPostgresText(value)}'::date"

    /**
     * PostgreSQL's ISO text for a date: `yyyy-MM-dd`, with the `BC` era instead of
     * ISO's signed year before 1 AD (ISO year 0 = 1 BC, -4713 = 4714 BC).
     */
    private fun toPostgresText(value: LocalDate): String {
        val bc = value.year <= 0
        val yearOfEra = if (bc) 1 - value.year else value.year
        val text = "%04d-%02d-%02d".format(Locale.ROOT, yearOfEra, value.monthValue, value.dayOfMonth)
        return if (bc) "$text BC" else text
    }

    /** Parses PostgreSQL's `yyyy-MM-dd[ BC]` text form (ISO year = 1 - BC year). */
    private fun parseLiteral(text: String): LocalDate {
        val trimmed = text.trim()
        val bc = trimmed.endsWith(" BC")
        val (year, month, day) = trimmed.removeSuffix(" BC").split('-').map { it.toInt() }
        return LocalDate.of(if (bc) 1 - year else year, month, day)
    }
}

/** A GEDCOM date-bound column; see [GedcomDateColumnType]. */
fun Table.gedcomDate(name: String): Column<LocalDate> = registerColumn(name, GedcomDateColumnType())
