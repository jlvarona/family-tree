package com.moxietech.family_tree.server.config

import com.auth0.jwt.JWT
import com.moxietech.family_tree.module
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FT-067 live check, opt-in (`-PkeycloakIt=true`): a disposable Keycloak 26
 * imports this repo's realm, the seeded dev user signs in to the browser
 * client `family-tree-web` through authorization code + PKCE, and the issued
 * tokens are sent to the production [module] (backed by a disposable Postgres).
 * Everything runs on Testcontainers' random host ports, so it never touches a
 * Keycloak or Postgres already running on this machine.
 *
 * - the browser client's access token names the API in `aud` and gets `200`;
 * - the ID token from the same sign-in gets `401`;
 * - an access token for the same user from `admin-cli`, which has no audience
 *   mapper, gets `401`.
 */
class KeycloakBrowserTokenTest {

    companion object {
        private const val REALM = "family-tree"
        private const val CLIENT_ID = "family-tree-web"
        private const val REDIRECT_URI = "http://localhost:3000/callback"
        private const val USERNAME = "dev@example.com"
        private const val PASSWORD = "devpassword" // the seeded local dev user from realm-export.json

        /** Self-typed so Kotlin can infer Testcontainers' recursive generic. */
        private class KeycloakContainer : GenericContainer<KeycloakContainer>("quay.io/keycloak/keycloak:26.0")

        private var keycloak: KeycloakContainer? = null
        private var postgres: PostgreSQLContainer<*>? = null
        private var dataSource: HikariDataSource? = null

        private val enabled = System.getProperty("family_tree.keycloakIt") == "true"

        @JvmStatic
        @BeforeClass
        fun setUp() {
            assumeTrue("Opt-in: run with -PkeycloakIt=true", enabled)
            val realm = listOf("../ops/keycloak/realm-export.json", "ops/keycloak/realm-export.json")
                .map(::File).first { it.isFile }
            val kc = KeycloakContainer()
            kc.withCommand("start-dev", "--import-realm")
            kc.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            kc.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            kc.withCopyFileToContainer(MountableFile.forHostPath(realm.absolutePath), "/opt/keycloak/data/import/realm-export.json")
            kc.withExposedPorts(8080)
            kc.waitingFor(
                Wait.forHttp("/realms/$REALM/.well-known/openid-configuration")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)),
            )
            keycloak = kc
            kc.start()
            val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
            postgres = pg
            pg.start()
            dataSource = DatabaseConfig.createDataSource(
                DatabaseSettings(jdbcUrl = pg.jdbcUrl, user = pg.username, password = pg.password),
            ).also {
                DatabaseConfig.migrate(it)
                DatabaseConfig.connectExposed(it)
                AppDatabase.init(it)
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            dataSource?.close()
            postgres?.stop()
            keycloak?.stop()
        }

        private val issuer get() = "http://${keycloak!!.host}:${keycloak!!.getMappedPort(8080)}/realms/$REALM"
    }

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    @Test
    fun `the browser client's access token reaches the API and its ID token does not`() {
        val tokens = signInThroughBrowserFlow()
        val accessToken = tokens.getValue("access_token")
        val idToken = tokens.getValue("id_token")

        val access = JWT.decode(accessToken)
        assertTrue(DEFAULT_API_AUDIENCE in access.audience.orEmpty(), "aud=${access.audience}")
        assertEquals(ACCESS_TOKEN_TYPE, access.getClaim("typ").asString())
        assertEquals(CLIENT_ID, access.getClaim("azp").asString())
        assertFalse(DEFAULT_API_AUDIENCE in JWT.decode(idToken).audience.orEmpty(), "ID token must not carry the API audience")

        callMe(accessToken) { status, body ->
            assertEquals(HttpStatusCode.OK, status, body)
            assertTrue("\"email\":\"$USERNAME\"" in body, body)
        }
        callMe(idToken) { status, _ -> assertEquals(HttpStatusCode.Unauthorized, status) }
    }

    @Test
    fun `an access token issued to a client without the API audience is rejected`() {
        val token = postForm(
            "$issuer/protocol/openid-connect/token",
            mapOf("grant_type" to "password", "client_id" to "admin-cli", "username" to USERNAME, "password" to PASSWORD),
        ).let { tokenResponse(it).getValue("access_token") }
        assertFalse(DEFAULT_API_AUDIENCE in JWT.decode(token).audience.orEmpty())
        callMe(token) { status, _ -> assertEquals(HttpStatusCode.Unauthorized, status) }
    }

    private fun callMe(token: String, check: suspend (HttpStatusCode, String) -> Unit) = testApplication {
        // Every field explicit, so ambient OIDC_* variables can't point this at another Keycloak.
        application {
            module(
                OidcSettings(
                    issuer = issuer,
                    jwksUri = "$issuer/protocol/openid-connect/certs",
                    audience = DEFAULT_API_AUDIENCE,
                ),
            )
        }
        val response = client.get("/api/v1/me") { bearerAuth(token) }
        check(response.status, response.bodyAsText())
    }

    /** Authorization code + PKCE, as a browser would do it, without a redirect listener. */
    private fun signInThroughBrowserFlow(): Map<String, String> {
        val verifier = base64Url(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = base64Url(ByteArray(12).also { SecureRandom().nextBytes(it) })
        val authorize = "$issuer/protocol/openid-connect/auth?" + form(
            mapOf(
                "client_id" to CLIENT_ID, "response_type" to "code", "scope" to "openid",
                "redirect_uri" to REDIRECT_URI, "state" to state,
                "code_challenge" to challenge, "code_challenge_method" to "S256",
            ),
        )
        val loginPage = http.send(HttpRequest.newBuilder(URI(authorize)).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, loginPage.statusCode(), "login page")
        val action = Regex("""<form[^>]*id="kc-form-login"[^>]*action="([^"]+)"""").find(loginPage.body())
            ?.groupValues?.get(1)?.replace("&amp;", "&")
            ?: error("Keycloak login form not found")

        // Forward the login page's session cookies to the form POST, as a browser would.
        // Only to the same origin: the form must post back to this Keycloak.
        val issuerUri = URI(issuer)
        val actionUri = URI(action)
        assertEquals(
            Triple(issuerUri.scheme, issuerUri.host, issuerUri.port),
            Triple(actionUri.scheme, actionUri.host, actionUri.port),
            "login form must post to the same origin",
        )
        val cookies = loginPage.headers().allValues("Set-Cookie")
            .map { it.substringBefore(';').trim() }
            .filter { it.isNotEmpty() }
            .joinToString("; ")
        val login = postForm(
            action,
            mapOf("username" to USERNAME, "password" to PASSWORD, "credentialId" to ""),
            cookies.ifEmpty { null },
        )
        assertEquals(302, login.statusCode(), "login should redirect back to the client")
        val location = URI(login.headers().firstValue("Location").orElseThrow())
        val query = location.rawQuery.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to java.net.URLDecoder.decode(v, Charsets.UTF_8)
        }
        assertEquals(state, query["state"])
        val code = query["code"] ?: error("No authorization code in redirect")

        return tokenResponse(
            postForm(
                "$issuer/protocol/openid-connect/token",
                mapOf(
                    "grant_type" to "authorization_code", "client_id" to CLIENT_ID, "code" to code,
                    "redirect_uri" to REDIRECT_URI, "code_verifier" to verifier,
                ),
            ),
        )
    }

    private fun tokenResponse(response: HttpResponse<String>): Map<String, String> {
        assertEquals(200, response.statusCode(), "token endpoint")
        return Json.parseToJsonElement(response.body()).jsonObject
            .filterValues { it is kotlinx.serialization.json.JsonPrimitive }
            .mapValues { it.value.jsonPrimitive.content }
    }

    private fun postForm(url: String, fields: Map<String, String>, cookie: String? = null): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI(url))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .apply { if (cookie != null) header("Cookie", cookie) }
            .POST(HttpRequest.BodyPublishers.ofString(form(fields)))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun form(fields: Map<String, String>) =
        fields.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}" }

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
