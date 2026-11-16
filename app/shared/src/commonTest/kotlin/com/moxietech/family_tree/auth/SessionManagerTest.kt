package com.moxietech.family_tree.auth

import com.moxietech.family_tree.FakeBridge
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionManagerTest {

    private val config = WebAppConfig(issuer = "http://localhost:8081/realms/family-tree", clientId = "family-tree-web")

    /** Records token-endpoint calls and answers with the next queued response. */
    private class TokenEndpoint {
        val calls = mutableListOf<Parameters>()
        val responses = ArrayDeque<Pair<HttpStatusCode, String>>()

        val client = HttpClient(
            MockEngine { request ->
                calls += (request.body as FormDataContent).formData
                val (status, body) = responses.removeFirst()
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
    }

    private var now = 0L
    private val bridge = FakeBridge()
    private val endpoint = TokenEndpoint()

    // The background refresh runs on a scheduler these tests never advance.
    private val idleScope = CoroutineScope(StandardTestDispatcher(TestCoroutineScheduler()))
    private val session = SessionManager(config, bridge, endpoint.client, idleScope, clock = { now })

    private fun pending(): PendingAuthorization =
        Json.decodeFromString(PendingAuthorization.serializer(), bridge.storage.getValue(SessionManager.PENDING_KEY))

    private fun idToken(sub: String, nonce: String): String {
        fun part(json: String) = Base64Url.encode(json.encodeToByteArray())
        return part("""{"alg":"none"}""") + "." + part("""{"sub":"$sub","nonce":"$nonce","typ":"ID"}""") + ".sig"
    }

    private fun tokens(access: String, nonce: String, refresh: String = "refresh-1") =
        """{"access_token":"$access","token_type":"Bearer","expires_in":300,"refresh_token":"$refresh",""" +
            """"refresh_expires_in":1800,"id_token":"${idToken("user-1", nonce)}"}"""

    /** Signs in from [route] and lands on the callback with Keycloak's code. */
    private suspend fun signInFrom(route: String = "/trees/t1") {
        bridge.url = "http://localhost:3000/#$route"
        session.signIn(route)
        val request = pending()
        endpoint.responses += HttpStatusCode.OK to tokens("access-1", request.nonce)
        bridge.url = "http://localhost:3000/?state=${request.state}&session_state=x&code=code-1"
        session.start()
    }

    @Test
    fun signInSendsAnS256ChallengeAndKeepsOnlyTheOneTimeRequest() {
        bridge.url = "http://localhost:3000/#/trees/t1"
        session.signIn("/trees/t1")

        val request = pending()
        val sent = ParsedUrl.parse(bridge.navigations.single())
        assertEquals(config.authorizationEndpoint, sent.base)
        assertEquals("code", sent.query["response_type"])
        assertEquals("family-tree-web", sent.query["client_id"])
        assertEquals("http://localhost:3000/", sent.query["redirect_uri"])
        assertEquals("openid", sent.query["scope"])
        assertEquals("S256", sent.query["code_challenge_method"])
        assertEquals(Pkce.s256Challenge(request.codeVerifier), sent.query["code_challenge"])
        assertEquals(request.state, sent.query["state"])
        assertEquals(request.nonce, sent.query["nonce"])
        assertNull(sent.query["prompt"])
        assertFalse(request.codeVerifier in bridge.navigations.single(), "the verifier must not leave the tab")
        assertEquals(43, request.codeVerifier.length)
        assertEquals("/trees/t1", request.returnRoute)
        assertEquals(AuthState.Redirecting, session.state.value)
    }

    @Test
    fun callbackWithMatchingStateExchangesTheCodeAndCleansTheUrl() = runTest {
        signInFrom("/trees/t1")

        val form = endpoint.calls.single()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("code-1", form["code"])
        assertEquals("http://localhost:3000/", form["redirect_uri"])
        assertEquals("family-tree-web", form["client_id"])
        assertTrue(form["code_verifier"]!!.length == 43)
        assertNull(form["client_secret"])

        assertEquals(AuthState.SignedIn("user-1"), session.state.value)
        assertEquals("http://localhost:3000/#/trees/t1", bridge.url)
        assertFalse(bridge.replacedUrls.any { "code=" in it || "state=" in it })
        assertNull(bridge.storage[SessionManager.PENDING_KEY])
        // Only the signed-in flag is stored; tokens stay in memory.
        assertEquals(setOf(SessionManager.ACTIVE_KEY), bridge.storage.keys)
        assertFalse(bridge.storage.values.any { "access-1" in it || "refresh-1" in it })
        assertEquals(TokenResult.Token("access-1"), session.accessToken())
    }

    @Test
    fun callbackWithAnotherStateIsRejectedWithoutATokenRequest() = runTest {
        session.signIn("/")
        bridge.url = "http://localhost:3000/?state=forged&code=code-1"
        session.start()

        assertTrue(endpoint.calls.isEmpty())
        assertEquals(AuthState.SignedOut(SignedOutReason.SIGN_IN_NOT_VERIFIED), session.state.value)
        assertEquals("http://localhost:3000/", bridge.url)
        assertNull(bridge.storage[SessionManager.PENDING_KEY])
        assertEquals(TokenResult.Expired, session.accessToken())
    }

    @Test
    fun replayedCallbackFindsNoPendingRequest() = runTest {
        signInFrom()
        val replay = "http://localhost:3000/?state=anything&code=code-1"
        val second = SessionManager(config, bridge, endpoint.client, idleScope, clock = { now })
        bridge.url = replay
        second.start()

        assertEquals(1, endpoint.calls.size)
        assertEquals(AuthState.SignedOut(SignedOutReason.SIGN_IN_NOT_VERIFIED), second.state.value)
    }

    @Test
    fun idTokenWithAnotherNonceIsRejected() = runTest {
        session.signIn("/")
        val request = pending()
        endpoint.responses += HttpStatusCode.OK to tokens("access-1", nonce = "other")
        bridge.url = "http://localhost:3000/?state=${request.state}&code=code-1"
        session.start()

        assertEquals(AuthState.SignedOut(SignedOutReason.SIGN_IN_NOT_VERIFIED), session.state.value)
        assertEquals(TokenResult.Expired, session.accessToken())
        assertNull(bridge.storage[SessionManager.ACTIVE_KEY])
    }

    @Test
    fun failedCodeExchangeIsReportedAsSignInFailure() = runTest {
        session.signIn("/")
        val request = pending()
        endpoint.responses += HttpStatusCode.BadRequest to """{"error":"invalid_grant"}"""
        bridge.url = "http://localhost:3000/?state=${request.state}&code=code-1"
        session.start()

        assertEquals(AuthState.SignedOut(SignedOutReason.SIGN_IN_FAILED), session.state.value)
    }

    @Test
    fun reloadWhileSignedInAsksKeycloakSilentlyForTheSameRoute() = runTest {
        bridge.storage[SessionManager.ACTIVE_KEY] = "1"
        bridge.url = "http://localhost:3000/#/trees/t1?view=ancestors"
        session.start()

        val sent = ParsedUrl.parse(bridge.navigations.single())
        assertEquals("none", sent.query["prompt"])
        assertEquals("/trees/t1?view=ancestors", pending().returnRoute)
        assertTrue(pending().silent)
        assertEquals(AuthState.Redirecting, session.state.value)
    }

    @Test
    fun firstVisitDoesNotRedirect() = runTest {
        session.start()

        assertTrue(bridge.navigations.isEmpty())
        assertEquals(AuthState.SignedOut(), session.state.value)
    }

    @Test
    fun endedKeycloakSessionOnReloadShowsAnExpiredSession() = runTest {
        bridge.storage[SessionManager.ACTIVE_KEY] = "1"
        bridge.url = "http://localhost:3000/#/trees/t1"
        session.start()
        val request = pending()
        bridge.url = "http://localhost:3000/?error=login_required&state=${request.state}"
        session.start()

        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED), session.state.value)
        assertNull(bridge.storage[SessionManager.ACTIVE_KEY])
        assertEquals("http://localhost:3000/#/trees/t1", bridge.url)
        assertTrue(endpoint.calls.isEmpty())
    }

    @Test
    fun expiringAccessTokenIsRefreshed() = runTest {
        signInFrom()
        now += 300_000 - SessionManager.REFRESH_SKEW_MS
        endpoint.responses += HttpStatusCode.OK to tokens("access-2", nonce = "ignored", refresh = "refresh-2")

        assertEquals(TokenResult.Token("access-2"), session.accessToken())
        val form = endpoint.calls.last()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("refresh-1", form["refresh_token"])
        assertEquals(TokenResult.Token("access-2"), session.accessToken())
        assertEquals(2, endpoint.calls.size)
    }

    @Test
    fun rejectedRefreshEndsTheSession() = runTest {
        signInFrom()
        endpoint.responses += HttpStatusCode.BadRequest to """{"error":"invalid_grant"}"""

        assertEquals(TokenResult.Expired, session.onRejected("access-1"))
        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED), session.state.value)
        assertNull(bridge.storage[SessionManager.ACTIVE_KEY])
        assertEquals(TokenResult.Expired, session.accessToken())
    }

    @Test
    fun unreachableKeycloakKeepsTheSession() = runTest {
        signInFrom()
        endpoint.responses += HttpStatusCode.ServiceUnavailable to ""

        assertEquals(TokenResult.Unavailable, session.onRejected("access-1"))
        assertIs<AuthState.SignedIn>(session.state.value)
    }

    @Test
    fun refreshTokenPastItsLifetimeEndsTheSessionWithoutARequest() = runTest {
        signInFrom()
        now += 1_800_000

        assertEquals(TokenResult.Expired, session.accessToken())
        assertEquals(1, endpoint.calls.size)
        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED), session.state.value)
    }

    @Test
    fun signOutForgetsTheSessionThenEndsItAtKeycloak() = runTest {
        signInFrom()
        session.signOut()

        assertEquals(AuthState.SignedOut(), session.state.value)
        assertTrue(bridge.storage.isEmpty())
        assertEquals(TokenResult.Expired, session.accessToken())
        val logout = ParsedUrl.parse(bridge.navigations.last())
        assertEquals(config.endSessionEndpoint, logout.base)
        assertEquals("family-tree-web", logout.query["client_id"])
        assertTrue(logout.query["id_token_hint"]!!.isNotEmpty())
        assertNull(logout.query["post_logout_redirect_uri"])
    }
}
