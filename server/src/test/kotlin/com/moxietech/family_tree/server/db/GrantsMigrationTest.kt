package com.moxietech.family_tree.server.db

import org.flywaydb.core.Flyway
import org.junit.ClassRule
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * FT-010: V7 adds `tree_grant`, `tree_graph_generation`, `grant_scope` and
 * `grant_scope_state` to a database migrated to V6 with data in it, and V8 adds the
 * `SIDE` direction. Existing rows and history are kept, every existing tree gets its
 * generation row, and the constraints hold: same-tree anchor, membership and scope
 * person; known directions; positive depth; anchors block a person delete while cache
 * rows cascade.
 */
class GrantsMigrationTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }

    @Test
    fun `V7 and V8 keep V6 data, backfill generations and enforce grant and scope constraints`() {
        val db = freshDatabase("v7_grants")
        migrate(db, target = "6")
        db.connection.use { c ->
            val owner = c.string("insert into account (email, display_name) values ('v7@example.com', 'Owner') returning id")!!
            val relative = c.string("insert into account (email, display_name) values ('v7r@example.com', 'Relative') returning id")!!
            val tree = c.string("insert into tree (name, owner_account_id) values ('Family', '$owner') returning id")!!
            val other = c.string("insert into tree (name, owner_account_id) values ('Other', '$owner') returning id")!!
            val mum = c.string("insert into person (tree_id, given_names, sex) values ('$tree', 'Carmen', 'FEMALE') returning id")!!
            val kid = c.string("insert into person (tree_id, given_names, sex) values ('$tree', 'Ana', 'FEMALE') returning id")!!
            val outsider = c.string("insert into person (tree_id, given_names, sex) values ('$other', 'Otro', 'MALE') returning id")!!
            val union = c.string("insert into union_record (tree_id, partner1_id, type) values ('$tree', '$mum', 'UNKNOWN') returning id")!!
            c.exec("insert into union_child (union_id, child_id, relation, tree_id) values ('$union', '$kid', 'BIRTH', '$tree')")
            c.exec("insert into membership (tree_id, account_id, status) values ('$tree', '$owner', 'ACTIVE'), ('$tree', '$relative', 'ACTIVE')")
            c.exec("insert into membership (tree_id, account_id, status) values ('$other', '$owner', 'ACTIVE')")

            migrate(db, target = "7")
            assertEquals("7", c.string("select max(version) from flyway_schema_history where success"))
            // V7 refused SIDE until its depth rule was decided; V8 accepts it.
            assertFailsWith<PSQLException>("V7 has no SIDE") {
                c.exec(
                    "insert into tree_grant (id, tree_id, account_id, role, anchor_person_id, direction, include_partners, created_by_account_id) " +
                        "values (gen_random_uuid(), '$tree', '$relative', 'VIEWER', '$mum', 'SIDE', false, '$owner')",
                )
            }
            migrate(db, target = "8")

            assertEquals("8", c.string("select max(version) from flyway_schema_history where success"))
            assertEquals("Carmen|1", c.string("select given_names || '|' || version from person where id = '$mum'"))
            assertEquals("1", c.string("select count(*) from union_child where union_id = '$union'"))
            assertEquals("0|0", c.string("select string_agg(generation::text, '|' order by tree_id) from tree_graph_generation"))
            assertEquals("2", c.string("select count(*) from tree_graph_generation where tree_id in ('$tree', '$other')"))

            fun grant(
                anchor: String,
                account: String = relative,
                treeId: String = tree,
                direction: String = "DESCENDANTS",
                depth: String = "null",
            ): String = c.string(
                """
                insert into tree_grant (id, tree_id, account_id, role, anchor_person_id, direction, depth, include_partners, created_by_account_id)
                values (gen_random_uuid(), '$treeId', '$account', 'VIEWER', '$anchor', '$direction', $depth, true, '$owner')
                returning id
                """.trimIndent(),
            )!!

            val granted = grant(mum)
            assertFailsWith<PSQLException>("anchor in another tree") { grant(outsider) }
            assertFailsWith<PSQLException>("account without membership in the tree") { grant(outsider, account = relative, treeId = other) }
            grant(mum, direction = "SIDE", depth = "2")
            grant(mum, direction = "WHOLE_TREE", depth = "2") // depth is ignored, not forbidden (V8)
            assertFailsWith<PSQLException>("unknown direction") { grant(mum, direction = "SIDEWAYS") }
            assertFailsWith<PSQLException>("depth is positive") { grant(mum, depth = "0") }

            c.exec("insert into grant_scope (grant_id, tree_id, person_id) values ('$granted', '$tree', '$mum'), ('$granted', '$tree', '$kid')")
            c.exec("insert into grant_scope_state (grant_id, tree_id, grant_version, graph_generation) values ('$granted', '$tree', 1, 0)")
            assertFailsWith<PSQLException>("scope person in another tree") {
                c.exec("insert into grant_scope (grant_id, tree_id, person_id) values ('$granted', '$tree', '$outsider')")
            }
            assertFailsWith<PSQLException>("scope state filed under another tree") {
                c.exec("insert into grant_scope_state (grant_id, tree_id, grant_version, graph_generation) values ('$granted', '$other', 1, 0)")
            }

            // The anchor is a real reference; a cache row is not.
            assertFailsWith<PSQLException>("anchor blocks the delete") { c.exec("delete from person where id = '$mum'") }
            c.exec("delete from union_child where child_id = '$kid'")
            c.exec("delete from person where id = '$kid'")
            assertEquals("1", c.string("select count(*) from grant_scope where grant_id = '$granted'"))

            // Removing the grant removes its cache.
            c.exec("delete from tree_grant where id = '$granted'")
            assertEquals("0|0", c.string("select (select count(*) from grant_scope) || '|' || (select count(*) from grant_scope_state)"))
        }
    }

    private fun freshDatabase(name: String): DataSource {
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

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.string(sql: String): String? =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }
}
