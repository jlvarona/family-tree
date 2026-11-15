package com.moxietech.family_tree.server.config

import com.moxietech.family_tree.module
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FT-067 acceptance through the production verifier: the real [module] with
 * [configureOidc], fetching keys over HTTP from a controlled JWKS ([TestJwks])
 * and serving `GET /api/v1/me` against a disposable Postgres. Only a correctly
 * signed, current access token whose `aud` names the API gets in; every other
 * credential is a `401`.
 */
class OidcAccessTokenTest {

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

    /** Calls `GET /api/v1/me` on the production module with [token]. */
    private fun me(token: String, oidc: OidcSettings = settings, check: suspend (HttpStatusCode, String) -> Unit) =
        testApplication {
            application { module(oidc) }
            val response = client.get("/api/v1/me") { bearerAuth(token) }
            check(response.status, response.bodyAsText())
        }

    private fun assertRejected(token: String, oidc: OidcSettings = settings) =
        me(token, oidc) { status, _ -> assertEquals(HttpStatusCode.Unauthorized, status) }

    @Test
    fun `a signed, current access token for the API succeeds`() =
        me(jwks.accessToken(ISSUER, email = "audience-ok@example.com")) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertTrue("\"email\":\"audience-ok@example.com\"" in body, body)
        }

    @Test
    fun `a single string audience naming the API succeeds`() =
        me(jwks.accessToken(ISSUER, audience = arrayOf(DEFAULT_API_AUDIENCE), email = "single-aud@example.com")) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
        }

    @Test
    fun `a token without an audience is rejected`() = assertRejected(jwks.accessToken(ISSUER, audience = null))

    @Test
    fun `a token for another audience is rejected`() =
        assertRejected(jwks.accessToken(ISSUER, audience = arrayOf("account", "some-other-api")))

    @Test
    fun `an ID token is rejected even when its audience names the API`() {
        // Keycloak ID tokens carry typ=ID and aud=<client id>; the type check must hold on its own.
        assertRejected(jwks.accessToken(ISSUER, audience = arrayOf("family-tree-web"), type = "ID"))
        assertRejected(jwks.accessToken(ISSUER, audience = arrayOf(DEFAULT_API_AUDIENCE), type = "ID"))
    }

    @Test
    fun `a token without a type claim is rejected`() = assertRejected(jwks.accessToken(ISSUER, type = null))

    @Test
    fun `an expired token is rejected`() =
        assertRejected(jwks.accessToken(ISSUER, expiresAt = Instant.now().minusSeconds(60)))

    @Test
    fun `a token without an expiry is rejected`() = assertRejected(jwks.accessToken(ISSUER, expiresAt = null))

    @Test
    fun `a token with a null expiry is rejected`() =
        assertRejected(jwks.accessToken(ISSUER) { withNullClaim("exp") })

    @Test
    fun `a token with a non-numeric expiry is rejected`() =
        assertRejected(jwks.accessToken(ISSUER) { withClaim("exp", "invalid") })

    @Test
    fun `a token from another issuer is rejected`() =
        assertRejected(jwks.accessToken("http://keycloak.test/realms/other"))

    @Test
    fun `a token signed by an unknown key is rejected`() =
        assertRejected(jwks.accessToken(ISSUER, signWithRogueKey = true))

    @Test
    fun `the audience is configurable`() {
        val custom = settings.copy(audience = "family-tree-api-prod")
        assertRejected(jwks.accessToken(ISSUER), custom)
        me(jwks.accessToken(ISSUER, audience = arrayOf("family-tree-api-prod"), email = "custom-aud@example.com"), custom) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
        }
    }

    @Test
    fun `a blank audience is a configuration error`() {
        val error = assertFailsWith<IllegalArgumentException> { OidcSettings(issuer = ISSUER, jwksUri = jwks.jwksUri, audience = " ") }
        assertEquals("OIDC audience must not be blank", error.message)
    }
}
