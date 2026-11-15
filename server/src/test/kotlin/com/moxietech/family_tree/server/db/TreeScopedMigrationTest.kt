package com.moxietech.family_tree.server.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.ClassRule
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FT-065: V2 (same-tree foreign keys) on top of data written under V1.
 * Each test gets its own fresh database, migrates it to V1 only, plants
 * historical rows, then runs V2. Bad rows must stop the migration with
 * diagnostics and leave the schema untouched; good rows must be backfilled.
 */
class TreeScopedMigrationTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private var databaseCounter = 0
    }

    @Test
    fun `preflight stops on cross-tree historical links and names every offending row`() {
        val db = freshDatabase()
        migrate(db, target = "1")
        db.connection.use { c ->
            val a = tree(c)
            val b = tree(c)
            val pa = person(c, a)
            val pb = person(c, b)
            val pb2 = person(c, b)
            val placeB = place(c, b)
            val sourceA = source(c, a)
            val badUnion = union(c, a, pa, pb)
            val unionB = union(c, b, pb, pb2)
            c.exec("insert into union_child (union_id, child_id, relation) values ('$unionB', '$pa', 'BIRTH')")
            val badEvent = c.uuid("insert into event (tree_id, person_id, place_id, type) values ('$a', '$pa', '$placeB', 'BIRTH') returning id")
            c.exec("update membership set person_id = '$pb' where tree_id = '$a'")
            val crossTarget = c.uuid("insert into citation (source_id, target_type, target_id, confidence) values ('$sourceA', 'PERSON', '$pb', 1) returning id")
            val missingTarget = c.uuid("insert into citation (source_id, target_type, target_id, confidence) values ('$sourceA', 'EVENT', gen_random_uuid(), 1) returning id")

            val error = assertFailsWith<FlywayException> { migrate(db) }
            val message = generateSequence<Throwable>(error) { it.cause }.joinToString("\n") { it.message.orEmpty() }

            assertTrue("FT-065 preflight found cross-tree references" in message, message)
            assertTrue("union_record with a partner from another tree: $badUnion" in message, message)
            assertTrue("union_child (union:child) with a child from another tree: $unionB:$pa" in message, message)
            assertTrue("event whose person, union or place is in another tree: $badEvent" in message, message)
            assertTrue("membership (tree:account) linked to a person in another tree: $a:" in message, message)
            assertTrue(crossTarget in message && missingTarget in message, message)

            // The failed migration rolled back completely: no V2 column, V1 still current.
            assertFalse(c.columnExists("union_child", "tree_id"))
            assertEquals("1", c.string("select max(version) from flyway_schema_history where success"))
        }
    }

    @Test
    fun `consistent historical data is backfilled and constrained`() {
        val db = freshDatabase()
        migrate(db, target = "1")
        db.connection.use { c ->
            val a = tree(c)
            val p1 = person(c, a)
            val p2 = person(c, a)
            val kid = person(c, a)
            val placeA = place(c, a)
            val sourceA = source(c, a)
            val u = union(c, a, p1, p2)
            c.exec("insert into union_child (union_id, child_id, relation) values ('$u', '$kid', 'BIRTH')")
            c.exec("insert into event (tree_id, person_id, place_id, type) values ('$a', '$kid', '$placeA', 'BIRTH')")
            c.exec("update membership set person_id = '$p1' where tree_id = '$a'")
            c.exec("insert into citation (source_id, target_type, target_id, confidence) values ('$sourceA', 'UNION', '$u', 3)")

            migrate(db, target = "2")

            assertEquals(a, c.string("select tree_id from union_child where union_id = '$u'"))
            assertEquals(a, c.string("select tree_id from citation where source_id = '$sourceA'"))
            assertEquals("2", c.string("select max(version) from flyway_schema_history where success"))
        }
    }

    // --- Fixtures (plain SQL: V1's schema, not the current Exposed mappings) ---

    private fun freshDatabase(): DataSource {
        val name = "migration_test_${++databaseCounter}"
        postgres.createConnection("").use { it.createStatement().execute("create database $name") }
        return PGSimpleDataSource().apply {
            setURL(postgres.jdbcUrl.replaceAfterLast('/', name))
            user = postgres.username
            password = postgres.password
        }
    }

    private fun migrate(dataSource: DataSource, target: String? = null) {
        Flyway.configure().dataSource(dataSource).apply { if (target != null) target(target) }.load().migrate()
    }

    private fun tree(c: Connection): String {
        val account = c.uuid("insert into account (email, display_name) values (gen_random_uuid() || '@example.com', 'Owner') returning id")
        val tree = c.uuid("insert into tree (name, owner_account_id) values ('Family', '$account') returning id")
        c.exec("insert into membership (tree_id, account_id, status) values ('$tree', '$account', 'ACTIVE')")
        return tree
    }

    private fun person(c: Connection, tree: String) =
        c.uuid("insert into person (tree_id, given_names, sex) values ('$tree', 'Someone', 'UNKNOWN') returning id")

    private fun place(c: Connection, tree: String) =
        c.uuid("insert into place (tree_id, name) values ('$tree', 'Somewhere') returning id")

    private fun source(c: Connection, tree: String) =
        c.uuid("insert into source (tree_id, title, type) values ('$tree', 'Register', 'OTHER') returning id")

    private fun union(c: Connection, tree: String, p1: String, p2: String) =
        c.uuid("insert into union_record (tree_id, partner1_id, partner2_id, type) values ('$tree', '$p1', '$p2', 'MARRIED') returning id")

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.string(sql: String): String? =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }

    private fun Connection.uuid(sql: String): String = string(sql)!!

    private fun Connection.columnExists(table: String, column: String): Boolean =
        string("select 1 from information_schema.columns where table_name = '$table' and column_name = '$column'") != null
}
