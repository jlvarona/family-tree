package com.moxietech.family_tree.server.identity

import com.moxietech.family_tree.module
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DEFAULT_API_AUDIENCE
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.config.OidcSettings
import com.moxietech.family_tree.server.config.TestJwks
import com.moxietech.family_tree.server.identity.IdentityTestApi.createTree
import com.moxietech.family_tree.server.identity.IdentityTestApi.getTree
import com.moxietech.family_tree.server.identity.IdentityTestApi.me
import com.moxietech.family_tree.server.identity.IdentityTestApi.query
import com.moxietech.family_tree.server.identity.IdentityTestApi.treeIds
import com.moxietech.family_tree.server.identity.IdentityTestApi.uuid
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.flywaydb.core.Flyway
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.postgresql.util.PSQLException
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FT-003 backfill: data written before V3 (accounts keyed by email) is migrated
 * by the production Flyway path. Legacy accounts keep their IDs and every
 * foreign key, stay unbound and unreachable (an email claim alone links
 * nothing), are listed by `unbound_legacy_account`, and come back only through
 * `resolve_legacy_account`, which fails without changes on conflicting mappings.
 *
 * Each test gets a fresh database: migrated to V2, seeded, then migrated to the
 * latest version and served by the production module.
 */
class LegacyAccountBackfillTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val ISSUER = "http://keycloak.test/realms/family-tree"
        private const val EVIDENCE = "Keycloak admin export 2026-11-15, confirmed with the account holder (OPS-1)"

        private lateinit var jwks: TestJwks
        private var databaseCounter = 0

        @JvmStatic
        @BeforeClass
        fun startJwks() {
            jwks = TestJwks()
        }

        @JvmStatic
        @AfterClass
        fun stopJwks() {
            if (::jwks.isInitialized) jwks.close()
        }
    }

    private lateinit var dataSource: HikariDataSource

    // Seeded under V2, before identities existed.
    private lateinit var legacyOwner: String
    private lateinit var legacyMember: String
    private lateinit var tree: String
    private lateinit var person: String
    private lateinit var audit: String

    @Before
    fun seedLegacyDatabase() {
        val name = "legacy_backfill_${++databaseCounter}"
        postgres.createConnection("").use { it.createStatement().execute("create database $name") }
        dataSource = DatabaseConfig.createDataSource(
            DatabaseSettings(
                jdbcUrl = postgres.jdbcUrl.replaceAfterLast('/', name),
                user = postgres.username,
                password = postgres.password,
            ),
        )
        Flyway.configure().dataSource(dataSource).target("2").load().migrate()

        dataSource.connection.use { c ->
            legacyOwner = c.query("insert into account (email, display_name) values ('legacy-owner@example.com', 'Legacy Owner') returning id")!!
            legacyMember = c.query("insert into account (email, display_name, locale) values ('legacy-member@example.com', 'Legacy Member', 'es') returning id")!!
            tree = c.query("insert into tree (name, owner_account_id) values ('Legacy family', ?) returning id", uuid(legacyOwner))!!
            person = c.query(
                "insert into person (tree_id, given_names, sex, is_living, created_by_account_id) values (?, 'Abuela', 'FEMALE', false, ?) returning id",
                uuid(tree), uuid(legacyOwner),
            )!!
            c.query("insert into membership (tree_id, account_id, status) values (?, ?, 'ACTIVE') returning tree_id", uuid(tree), uuid(legacyOwner))
            c.query(
                "insert into membership (tree_id, account_id, person_id, status) values (?, ?, ?, 'ACTIVE') returning tree_id",
                uuid(tree), uuid(legacyMember), uuid(person),
            )
            audit = c.query(
                "insert into audit_log (tree_id, actor_account_id, entity_type, entity_id, action) values (?, ?, 'Person', ?, 'CREATE') returning id",
                uuid(tree), uuid(legacyOwner), person,
            )!!
        }

        // The production startup path: apply every pending migration, then connect.
        DatabaseConfig.migrate(dataSource)
        DatabaseConfig.connectExposed(dataSource)
        AppDatabase.init(dataSource)
    }

    @After
    fun closeDatabase() {
        if (::dataSource.isInitialized) dataSource.close()
    }

    private val settings get() = OidcSettings(issuer = ISSUER, jwksUri = jwks.jwksUri, audience = DEFAULT_API_AUDIENCE)

    private fun token(subject: String, email: String) = jwks.accessToken(ISSUER, email = email) { withSubject(subject) }

    private fun resolve(account: String, subject: String, evidence: String = EVIDENCE, replaceUnused: Boolean? = false) {
        dataSource.connection.use { c ->
            c.query("select resolve_legacy_account(?, ?, ?, ?, ?::boolean)", uuid(account), ISSUER, subject, evidence, replaceUnused)
        }
    }

    private fun unboundIds(): Set<String> = dataSource.connection.use { c ->
        c.prepareStatement("select id from unbound_legacy_account").use { st ->
            st.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    /** The legacy rows and every reference to them, exactly as seeded. */
    private fun assertLegacyReferencesIntact() = dataSource.connection.use { c ->
        assertEquals(legacyOwner, c.query("select owner_account_id from tree where id = ?", uuid(tree)))
        assertEquals(legacyOwner, c.query("select created_by_account_id from person where id = ?", uuid(person)))
        assertEquals(legacyOwner, c.query("select actor_account_id from audit_log where id = ?", uuid(audit)))
        assertEquals(
            "ACTIVE",
            c.query("select status from membership where tree_id = ? and account_id = ?", uuid(tree), uuid(legacyOwner)),
        )
        assertEquals(
            person,
            c.query("select person_id from membership where tree_id = ? and account_id = ?", uuid(tree), uuid(legacyMember)),
        )
    }

    private fun Connection.identityOf(account: String): String? =
        query("select oidc_issuer || ' ' || oidc_subject from account where id = ?", uuid(account))

    @Test
    fun `migration keeps legacy account IDs and references and leaves them unbound`() {
        assertLegacyReferencesIntact()
        dataSource.connection.use { c ->
            assertNull(c.identityOf(legacyOwner))
            assertNull(c.identityOf(legacyMember))
            assertEquals(
                "1 1 1 1",
                c.query(
                    "select owned_trees || ' ' || memberships || ' ' || people_created || ' ' || audit_entries from unbound_legacy_account where id = ?",
                    uuid(legacyOwner),
                ),
            )
        }
        assertEquals(setOf(legacyOwner, legacyMember), unboundIds())
    }

    @Test
    fun `a login with a legacy email gets a new account and no access`() = testApplication {
        application { module(settings) }
        val claimant = token("kc-claimant", "legacy-owner@example.com")

        val account = client.me(claimant)

        assertNotEquals(legacyOwner, account.id)
        assertTrue(client.treeIds(claimant).isEmpty())
        assertEquals(HttpStatusCode.Forbidden, client.getTree(claimant, tree).status)
        assertEquals(setOf(legacyOwner, legacyMember), unboundIds())
        assertLegacyReferencesIntact()
    }

    @Test
    fun `resolving from an authoritative mapping restores the same account and its trees`() = testApplication {
        application { module(settings) }
        resolve(legacyOwner, "kc-legacy-owner")
        resolve(legacyOwner, "kc-legacy-owner") // re-running a mapping is a no-op

        // The provider's current address differs from the legacy one; it is just refreshed.
        val owner = token("kc-legacy-owner", "owner-now@example.com")
        val account = client.me(owner)

        assertEquals(legacyOwner, account.id)
        assertEquals("owner-now@example.com", account.email)
        assertEquals(listOf(tree), client.treeIds(owner))
        assertEquals(HttpStatusCode.OK, client.getTree(owner, tree).status)
        assertEquals(setOf(legacyMember), unboundIds())
        dataSource.connection.use { c ->
            assertEquals("$ISSUER kc-legacy-owner", c.identityOf(legacyOwner))
            assertEquals(
                "1",
                c.query("select count(*) from account_identity_resolution where account_id = ? and evidence = ?", uuid(legacyOwner), EVIDENCE),
            )
            assertEquals(legacyOwner, c.query("select owner_account_id from tree where id = ?", uuid(tree)))
            assertEquals(legacyOwner, c.query("select created_by_account_id from person where id = ?", uuid(person)))
            assertEquals(legacyOwner, c.query("select actor_account_id from audit_log where id = ?", uuid(audit)))
        }
    }

    @Test
    fun `conflicting mappings fail and change nothing`() = testApplication {
        application { module(settings) }
        resolve(legacyOwner, "kc-a")

        fun assertRejected(expected: String, block: () -> Unit) {
            val error = assertFailsWith<PSQLException> { block() }
            assertTrue(expected in error.message.orEmpty(), error.message)
        }

        // The identity already belongs to another account.
        assertRejected("already belongs to account $legacyOwner") { resolve(legacyMember, "kc-a") }
        // The account is already bound to a different identity.
        assertRejected("already bound to a different identity") { resolve(legacyOwner, "kc-b") }
        // No evidence, or no subject.
        assertRejected("evidence must name") { resolve(legacyMember, "kc-member", evidence = " ") }
        assertRejected("issuer and subject are required") { resolve(legacyMember, "") }
        // An unknown account.
        assertRejected("does not exist") { resolve("00000000-0000-0000-0000-000000000000", "kc-member") }
        // A bound identity can't be edited directly either.
        assertRejected("binding cannot be changed") {
            dataSource.connection.use { c ->
                c.query("update account set oidc_subject = 'kc-b' where id = ? returning id", uuid(legacyOwner))
            }
        }
        // An account that already did something isn't replaced, even when asked to.
        val busy = token("kc-busy", "busy@example.com")
        val busyAccount = client.me(busy)
        client.createTree(busy, "Busy family")
        assertRejected("already belongs to account ${busyAccount.id} (") {
            resolve(legacyMember, "kc-busy", replaceUnused = true)
        }

        assertEquals(setOf(legacyMember), unboundIds())
        dataSource.connection.use { c ->
            assertEquals("$ISSUER kc-a", c.identityOf(legacyOwner))
            assertEquals("$ISSUER kc-busy", c.identityOf(busyAccount.id))
            assertEquals("1", c.query("select count(*) from account_identity_resolution"))
        }
        assertLegacyReferencesIntact()
        assertEquals(busyAccount.id, client.me(busy).id)
    }

    @Test
    fun `an empty account created by an early login can be replaced explicitly`() = testApplication {
        application { module(settings) }
        // The member signs in after the upgrade, before an operator resolved them.
        val member = token("kc-member", "member@example.com")
        val early = client.me(member)
        assertNotEquals(legacyMember, early.id)
        assertTrue(client.treeIds(member).isEmpty())

        // Without an explicit true the conflict fails safely: false and NULL both refuse.
        for (flag in listOf(false, null)) {
            val error = assertFailsWith<PSQLException>("replace flag $flag") {
                resolve(legacyMember, "kc-member", replaceUnused = flag)
            }
            assertTrue("already belongs to account ${early.id}" in error.message.orEmpty(), error.message)
            assertEquals(setOf(legacyMember), unboundIds() - legacyOwner, "replace flag $flag")
            dataSource.connection.use { c ->
                assertEquals("$ISSUER kc-member", c.identityOf(early.id), "replace flag $flag")
                assertNull(c.identityOf(legacyMember), "replace flag $flag")
                assertEquals("0", c.query("select count(*) from account_identity_resolution"), "replace flag $flag")
            }
        }
        assertEquals(early.id, client.me(member).id)

        resolve(legacyMember, "kc-member", replaceUnused = true)

        val restored = client.me(member)
        assertEquals(legacyMember, restored.id)
        // The authenticated request refreshed the legacy address to the provider's current one.
        assertEquals("member@example.com", restored.email)
        assertEquals(listOf(tree), client.treeIds(member))
        dataSource.connection.use { c ->
            assertEquals("member@example.com", c.query("select email from account where id = ?", uuid(legacyMember)))
            assertNull(c.query("select id from account where id = ?", uuid(early.id)))
            assertEquals(
                early.id,
                c.query("select replaced_account_id from account_identity_resolution where account_id = ?", uuid(legacyMember)),
            )
        }
        assertLegacyReferencesIntact()
    }
}
