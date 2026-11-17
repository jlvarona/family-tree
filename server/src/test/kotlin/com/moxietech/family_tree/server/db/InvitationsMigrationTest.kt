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
 * FT-011: V9 adds `invitation`, `invitation_grant`, a membership version and one
 * membership per linked person, on a database migrated to V8 with data in it.
 */
class InvitationsMigrationTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private val DIGEST_A = "'\\x" + "aa".repeat(32) + "'"
        private val DIGEST_B = "'\\x" + "bb".repeat(32) + "'"
    }

    @Test
    fun `V9 keeps V8 data and enforces invitation and linking constraints`() {
        val db = freshDatabase("v9_invitations")
        migrate(db, target = "8")
        db.connection.use { c ->
            val owner = c.string("insert into account (email, display_name) values ('v9@example.com', 'Owner') returning id")!!
            val relative = c.string("insert into account (email, display_name) values ('v9r@example.com', 'Relative') returning id")!!
            val tree = c.string("insert into tree (name, owner_account_id) values ('Family', '$owner') returning id")!!
            val other = c.string("insert into tree (name, owner_account_id) values ('Other', '$owner') returning id")!!
            val mum = c.string("insert into person (tree_id, given_names, sex) values ('$tree', 'Carmen', 'FEMALE') returning id")!!
            val kid = c.string("insert into person (tree_id, given_names, sex) values ('$tree', 'Ana', 'FEMALE') returning id")!!
            val outsider = c.string("insert into person (tree_id, given_names, sex) values ('$other', 'Otro', 'MALE') returning id")!!
            c.exec("insert into membership (tree_id, account_id, person_id, status) values ('$tree', '$owner', '$mum', 'ACTIVE'), ('$tree', '$relative', null, 'INVITED')")

            migrate(db)
            assertEquals("9", c.string("select max(version) from flyway_schema_history where success"))
            assertEquals("1|1", c.string("select string_agg(version::text, '|') from membership where tree_id = '$tree'"))

            // One membership per person.
            assertFailsWith<PSQLException>("person linked twice") {
                c.exec("update membership set person_id = '$mum' where account_id = '$relative'")
            }

            fun invitation(digest: String, person: String? = null, treeId: String = tree, expires: String = "now() + interval '1 day'"): String =
                c.string(
                    """
                    insert into invitation (id, tree_id, token_digest, person_id, expires_at, created_by_account_id)
                    values (gen_random_uuid(), '$treeId', $digest, ${person?.let { "'$it'" } ?: "null"}, $expires, '$owner')
                    returning id
                    """.trimIndent(),
                )!!

            val invited = invitation(DIGEST_A, person = kid)
            assertFailsWith<PSQLException>("same digest twice") { invitation(DIGEST_A) }
            assertFailsWith<PSQLException>("two pending invitations for one person") { invitation(DIGEST_B, person = kid) }
            assertFailsWith<PSQLException>("person in another tree") { invitation(DIGEST_B, person = outsider) }
            assertFailsWith<PSQLException>("digest is 32 bytes") { invitation("'\\x00'") }
            assertFailsWith<PSQLException>("expiry after creation") { invitation(DIGEST_B, expires = "now() - interval '1 second'") }

            fun proposedGrant(anchor: String, ordinal: Int, treeId: String = tree, depth: String = "null") = c.exec(
                """
                insert into invitation_grant (id, invitation_id, tree_id, ordinal, role, anchor_person_id, direction, depth, include_partners)
                values (gen_random_uuid(), '$invited', '$treeId', $ordinal, 'VIEWER', '$anchor', 'SIDE', $depth, true)
                """.trimIndent(),
            )
            proposedGrant(mum, 0)
            assertFailsWith<PSQLException>("anchor in another tree") { proposedGrant(outsider, 1) }
            assertFailsWith<PSQLException>("filed under another tree") { proposedGrant(mum, 1, treeId = other) }
            assertFailsWith<PSQLException>("depth is positive") { proposedGrant(mum, 1, depth = "0") }
            assertFailsWith<PSQLException>("ordinal is unique") { proposedGrant(mum, 0) }

            // Pending invitations are real references: their people can't be deleted.
            assertFailsWith<PSQLException>("invitation person blocks the delete") { c.exec("delete from person where id = '$kid'") }
            c.exec("update membership set person_id = null where account_id = '$owner'")
            assertFailsWith<PSQLException>("proposed anchor blocks the delete") { c.exec("delete from person where id = '$mum'") }

            // Deleting the invitation (accept or revoke) removes its proposed grants.
            c.exec("delete from invitation where id = '$invited'")
            assertEquals("0", c.string("select count(*) from invitation_grant"))
            c.exec("delete from person where id = '$kid'")
        }
    }

    @Test
    fun `V9 stops on a person linked to two memberships`() {
        val db = freshDatabase("v9_preflight")
        migrate(db, target = "8")
        db.connection.use { c ->
            val owner = c.string("insert into account (email, display_name) values ('p@example.com', 'Owner') returning id")!!
            val relative = c.string("insert into account (email, display_name) values ('pr@example.com', 'Relative') returning id")!!
            val tree = c.string("insert into tree (name, owner_account_id) values ('Family', '$owner') returning id")!!
            val mum = c.string("insert into person (tree_id, given_names, sex) values ('$tree', 'Carmen', 'FEMALE') returning id")!!
            c.exec("insert into membership (tree_id, account_id, person_id, status) values ('$tree', '$owner', '$mum', 'ACTIVE'), ('$tree', '$relative', '$mum', 'ACTIVE')")

            val failure = assertFailsWith<FlywayException> { migrate(db) }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { "linked to more than one membership" in (it.message ?: "") })
            assertEquals("8", c.string("select max(version) from flyway_schema_history where success"))
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
