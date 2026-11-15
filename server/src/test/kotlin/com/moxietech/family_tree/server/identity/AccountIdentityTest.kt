package com.moxietech.family_tree.server.identity

import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.module
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DEFAULT_API_AUDIENCE
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.config.OidcSettings
import com.moxietech.family_tree.server.config.TestJwks
import com.moxietech.family_tree.server.identity.IdentityTestApi.accountsWithIdentity
import com.moxietech.family_tree.server.identity.IdentityTestApi.createPlace
import com.moxietech.family_tree.server.identity.IdentityTestApi.createTree
import com.moxietech.family_tree.server.identity.IdentityTestApi.getTree
import com.moxietech.family_tree.server.identity.IdentityTestApi.me
import com.moxietech.family_tree.server.identity.IdentityTestApi.query
import com.moxietech.family_tree.server.identity.IdentityTestApi.treeIds
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FT-003 acceptance through the production module and verifier ([module] with
 * a controlled JWKS) against a disposable Postgres: the verified issuer and
 * subject select the account; email is profile data.
 */
class AccountIdentityTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val ISSUER = "http://keycloak.test/realms/family-tree"

        private lateinit var dataSource: HikariDataSource
        private lateinit var jwks: TestJwks

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dataSource = DatabaseConfig.createDataSource(
                DatabaseSettings(jdbcUrl = postgres.jdbcUrl, user = postgres.username, password = postgres.password),
            )
            DatabaseConfig.migrate(dataSource)
            DatabaseConfig.connectExposed(dataSource)
            AppDatabase.init(dataSource)
            jwks = TestJwks()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::jwks.isInitialized) jwks.close()
            if (::dataSource.isInitialized) dataSource.close()
        }
    }

    private val settings get() = OidcSettings(issuer = ISSUER, jwksUri = jwks.jwksUri, audience = DEFAULT_API_AUDIENCE)

    /** A Keycloak-shaped access token for [subject], currently using [email]. */
    private fun token(subject: String, email: String) = jwks.accessToken(ISSUER, email = email) { withSubject(subject) }

    @Test
    fun `parallel first requests through the route create one account`() = testApplication {
        application { module(settings) }
        val token = token("parallel-subject", "parallel@example.com")

        val responses = coroutineScope {
            (1..24).map { async(Dispatchers.IO) { client.get("/api/v1/me") { bearerAuth(token) } } }.awaitAll()
        }

        val ids = responses.map { response ->
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, body)
            Regex("\"id\":\"([^\"]+)\"").find(body)!!.groupValues[1]
        }
        assertEquals(1, ids.toSet().size, "every parallel first request resolves the same account")
        assertEquals(1L, dataSource.accountsWithIdentity(ISSUER, "parallel-subject"))
    }

    @Test
    fun `parallel first logins racing in PostgreSQL create one account`() {
        // A barrier releases every thread at once so the inserts contend on account_oidc_identity_key.
        val threads = 8
        val identity = OidcIdentity(ISSUER, "barrier-subject")
        val barrier = CyclicBarrier(threads)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (1..threads).map { n ->
                pool.submit<String> {
                    barrier.await(10, TimeUnit.SECONDS)
                    AccountRepository.getOrCreateByIdentity(identity, "barrier$n@example.com", "Racer", Locale.EN).id.value
                }
            }
            val ids = futures.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, ids.toSet().size)
            assertEquals(1L, dataSource.accountsWithIdentity(ISSUER, "barrier-subject"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `an email change keeps the account ID and tree access`() = testApplication {
        application { module(settings) }
        val before = token("alice-subject", "alice@old.example")
        val account = client.me(before)
        val tree = client.createTree(before, "Alice family")

        val after = token("alice-subject", "alice@new.example")
        val changed = client.me(after)

        assertEquals(account.id, changed.id)
        assertEquals("alice@new.example", changed.email)
        assertEquals(listOf(tree.id), client.treeIds(after))
        assertEquals(HttpStatusCode.OK, client.getTree(after, tree.id).status)
        assertEquals(HttpStatusCode.Created, client.createPlace(after, tree.id, "Sevilla").status)
        assertEquals(1L, dataSource.accountsWithIdentity(ISSUER, "alice-subject"))
        assertEquals(
            "alice@new.example",
            dataSource.query("select email from account where oidc_issuer = ? and oidc_subject = ?", ISSUER, "alice-subject"),
        )
    }

    @Test
    fun `a different subject using a former or current email inherits nothing`() = testApplication {
        application { module(settings) }
        val bobOld = token("bob-subject", "bob@example.com")
        val bob = client.me(bobOld)
        val tree = client.createTree(bobOld, "Bob family")
        val bobNew = token("bob-subject", "bob@new.example")
        assertEquals(bob.id, client.me(bobNew).id)

        // The provider hands Bob's former address to someone else, and another
        // identity shows up with his current one: email is not unique and links nothing.
        for (email in listOf("bob@example.com", "bob@new.example")) {
            val other = token("other-subject-$email", email)
            val stranger = client.me(other)
            assertNotEquals(bob.id, stranger.id, email)
            assertEquals(email, stranger.email)
            assertTrue(client.treeIds(other).isEmpty(), email)
            assertEquals(HttpStatusCode.Forbidden, client.getTree(other, tree.id).status, email)
            assertEquals(HttpStatusCode.Forbidden, client.createPlace(other, tree.id, "Lima").status, email)
        }

        assertEquals(listOf(tree.id), client.treeIds(bobNew))
        assertEquals(
            "0",
            dataSource.query(
                "select count(*) from membership where tree_id = ?::uuid and account_id <> ?::uuid",
                tree.id, bob.id,
            ),
        )
    }

    @Test
    fun `the same subject from another issuer is another account`() {
        val here = AccountRepository.getOrCreateByIdentity(OidcIdentity(ISSUER, "shared-subject"), "s@example.com", "S", Locale.EN)
        val elsewhere = AccountRepository.getOrCreateByIdentity(
            OidcIdentity("http://keycloak.test/realms/other", "shared-subject"), "s@example.com", "S", Locale.EN,
        )
        assertNotEquals(here.id, elsewhere.id)
    }
}
