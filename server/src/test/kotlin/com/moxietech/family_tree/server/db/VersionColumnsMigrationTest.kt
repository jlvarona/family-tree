package com.moxietech.family_tree.server.db

import org.flywaydb.core.Flyway
import org.junit.ClassRule
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * FT-001: V4 adds a version to place and union_child. Places and child links
 * written before V4 must start at version 1, like new rows, and keep every
 * other column, so clients that read them earlier can update them.
 */
class VersionColumnsMigrationTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }

    @Test
    fun `places and child links from before V4 start at version 1`() {
        val db = freshDatabase()
        migrate(db, target = "3")
        db.connection.use { c ->
            val account = c.string("insert into account (email, display_name) values ('v4@example.com', 'Owner') returning id")!!
            val tree = c.string("insert into tree (name, owner_account_id) values ('Family', '$account') returning id")!!
            fun person() = c.string("insert into person (tree_id, given_names, sex) values ('$tree', 'Someone', 'UNKNOWN') returning id")!!
            val p1 = person()
            val p2 = person()
            val kid = person()
            val place = c.string("insert into place (tree_id, name, hierarchy) values ('$tree', 'Sevilla', 'España') returning id")!!
            val union = c.string(
                "insert into union_record (tree_id, partner1_id, partner2_id, type) values ('$tree', '$p1', '$p2', 'MARRIED') returning id",
            )!!
            c.createStatement().use {
                it.execute("insert into union_child (tree_id, union_id, child_id, relation) values ('$tree', '$union', '$kid', 'STEP')")
            }

            migrate(db)

            assertEquals("4", c.string("select max(version) from flyway_schema_history where success"))
            assertEquals("1|Sevilla|España", c.string("select version || '|' || name || '|' || hierarchy from place where id = '$place'"))
            assertEquals("1|STEP", c.string("select version || '|' || relation from union_child where union_id = '$union'"))
            // A row inserted without naming the column also starts at 1.
            val later = c.string("insert into place (tree_id, name) values ('$tree', 'Cádiz') returning version")
            assertEquals("1", later)
        }
    }

    private fun freshDatabase(): DataSource {
        val name = "v4_migration_test"
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

    private fun Connection.string(sql: String): String? =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
}
