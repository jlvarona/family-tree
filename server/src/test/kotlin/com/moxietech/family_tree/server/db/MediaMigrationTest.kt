package com.moxietech.family_tree.server.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.ClassRule
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FT-008: V6 adds `media` and same-tree, attach-once references from
 * `person.profile_media_id` and `citation.media_id`, keeping existing rows and the
 * migration history. A value already in either column has nothing to point at, so
 * the migration stops with diagnostics instead of guessing.
 */
class MediaMigrationTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }

    @Test
    fun `V6 keeps existing people and citations and enforces tree-scoped, single-use media`() {
        val db = freshDatabase("v6_media_ok")
        migrate(db, target = "5")
        db.connection.use { c ->
            val account = c.string("insert into account (email, display_name) values ('v6@example.com', 'Owner') returning id")!!
            val tree = c.string("insert into tree (name, owner_account_id) values ('Family', '$account') returning id")!!
            val other = c.string("insert into tree (name, owner_account_id) values ('Other', '$account') returning id")!!
            val person = c.string("insert into person (tree_id, given_names, sex) values ('$tree', 'Ana', 'FEMALE') returning id")!!
            val source = c.string("insert into source (tree_id, title, type) values ('$tree', 'Register', 'CENSUS') returning id")!!
            c.exec("insert into citation (tree_id, source_id, target_type, target_id, confidence) values ('$tree', '$source', 'PERSON', '$person', 2)")

            migrate(db, target = "6")

            assertEquals("6", c.string("select max(version) from flyway_schema_history where success"))
            assertEquals("Ana|1", c.string("select given_names || '|' || version from person where id = '$person'"))
            assertEquals("1", c.string("select count(*) from citation where media_id is null"))

            fun media(treeId: String, n: Int, status: String = "READY"): String {
                val ready = if (status == "READY") "'media/$treeId/$n/sha', 10, 1, 1, 'sha', now()" else "null, null, null, null, null, null"
                return c.string(
                    """
                    insert into media (id, tree_id, purpose, status, content_type, upload_byte_size, upload_key,
                                       storage_key, byte_size, width, height, sha256, finalized_at, uploaded_by_account_id)
                    values (gen_random_uuid(), '$treeId', 'PROFILE_PHOTO', '$status', 'image/jpeg', 10, 'uploads/$treeId/$n', $ready, '$account')
                    returning id
                    """.trimIndent(),
                )!!
            }
            val photo = media(tree, 1)
            val foreign = media(other, 2)
            c.exec("update person set profile_media_id = '$photo' where id = '$person'")
            assertFailsWith<PSQLException>("another tree's media") {
                c.exec("update person set profile_media_id = '$foreign' where id = '$person'")
            }
            val second = c.string("insert into person (tree_id, given_names, sex) values ('$tree', 'Bea', 'FEMALE') returning id")!!
            assertFailsWith<PSQLException>("one photo per media row, checked at commit") {
                c.autoCommit = false
                try {
                    c.exec("update person set profile_media_id = '$photo' where id = '$second'")
                    c.commit()
                } finally {
                    c.rollback()
                    c.autoCommit = true
                }
            }
            assertFailsWith<PSQLException>("READY needs its details") {
                c.exec("update media set storage_key = null where id = '$photo'")
            }
            assertFailsWith<PSQLException>("only JPEG and PNG") {
                c.exec("update media set content_type = 'image/gif' where id = '$photo'")
            }
        }
    }

    @Test
    fun `V6 stops with diagnostics when a media reference already exists`() {
        val db = freshDatabase("v6_media_preflight")
        migrate(db, target = "5")
        val person = db.connection.use { c ->
            val account = c.string("insert into account (email, display_name) values ('v6p@example.com', 'Owner') returning id")!!
            val tree = c.string("insert into tree (name, owner_account_id) values ('Family', '$account') returning id")!!
            c.string("insert into person (tree_id, given_names, sex, profile_media_id) values ('$tree', 'Ana', 'FEMALE', gen_random_uuid()) returning id")!!
        }
        val error = assertFailsWith<FlywayException> { migrate(db) }
        assertTrue("FT-008 preflight" in error.message.orEmpty() && person in error.message.orEmpty(), error.message)
        db.connection.use { c ->
            assertEquals("5", c.string("select max(version) from flyway_schema_history where success"))
            assertEquals("f", c.string("select exists (select 1 from information_schema.tables where table_name = 'media')")?.take(1))
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
