package com.moxietech.family_tree.auth

import com.moxietech.family_tree.FakeBridge
import com.moxietech.family_tree.RFC7636_CHALLENGE
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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

    private val config = WebAppConfig(issuer = TEST_ISSUER, clientId = TEST_CLIENT)

    /** Records token-endpoint calls and answers with the next queued response, optionally after a gate. */
    private class TokenEndpoint {
        val calls = mutableListOf<Parameters>()
        val responses = ArrayDeque<Pair<HttpStatusCode, String>>()
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        val client = HttpClient(
            MockEngine { request ->
                calls += (request.body as FormDataContent).formData
                gate?.let {
                    entered.complete(Unit)
                    it.await()
                }
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
    private val session = newSession()

    private fun newSession() =
        SessionManager(config, bridge, endpoint.client, idleScope, clock = { now }, nowSeconds = { TEST_NOW })

    private fun pending(): PendingAuthorization =
        Json.decodeFromString(PendingAuthorization.serializer(), bridge.storage.getValue(SessionManager.PENDING_KEY))

    /** The default ID token is a refresh-style one: no nonce, same subject. */
    private fun tokens(
        access: String,
        idToken: String = testJwt(idTokenPayload(nonce = null)),
        refresh: String = "refresh-1",
        expiresIn: Long = 300,
    ) = """{"access_token":"$access","token_type":"Bearer","expires_in":$expiresIn,"refresh_token":"$refresh",""" +
        """"refresh_expires_in":1800,"id_token":"$idToken"}"""

    /** The pending request's nonce, put into the ID token Keycloak would return. */
    private fun idTokenFor(request: PendingAuthorization, payload: (String) -> String = { idTokenPayload(nonce = it) }) =
        testJwt(payload(request.nonce))

    /** Signs in from [route] and lands on the fragment callback with Keycloak's code. */
    private suspend fun signInFrom(route: String = "/trees/t1", expiresIn: Long = 300) {
        bridge.url = "http://localhost:3000/#$route"
        session.signIn(route)
        val request = pending()
        endpoint.responses += HttpStatusCode.OK to tokens("access-1", idTokenFor(request), expiresIn = expiresIn)
        bridge.url = "http://localhost:3000/#state=${request.state}&session_state=x&iss=y&code=code-1"
        session.start()
    }

    private fun assertNoSecretsInUrls() {
        assertFalse(bridge.replacedUrls.any { "code=" in it || "state=" in it }, bridge.replacedUrls.toString())
        assertFalse("code=" in bridge.url || "state=" in bridge.url, bridge.url)
    }

    @Test
    fun signInSendsAWebCryptoS256ChallengeInFragmentMode() = runTest {
        bridge.url = "http://localhost:3000/#/trees/t1"
        session.signIn("/trees/t1")

        val request = pending()
        val sent = UrlParts(bridge.navigations.single())
        val query = sent.queryParameters
        assertEquals(config.authorizationEndpoint, sent.base)
        assertEquals("code", query["response_type"])
        assertEquals("fragment", query["response_mode"])
        assertEquals(TEST_CLIENT, query["client_id"])
        assertEquals("http://localhost:3000/", query["redirect_uri"])
        assertEquals("openid", query["scope"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(RFC7636_CHALLENGE, query["code_challenge"])
        assertEquals(listOf(request.codeVerifier), bridge.digestInputs, "Web Crypto hashes the verifier")
        assertEquals(request.state, query["state"])
        assertEquals(request.nonce, query["nonce"])
        assertNull(query["prompt"])
        assertFalse(request.codeVerifier in bridge.navigations.single(), "the verifier must not leave the tab")
        assertEquals(43, request.codeVerifier.length)
        assertEquals("/trees/t1", request.returnRoute)
        assertEquals(AuthState.Redirecting, session.state.value)
    }

    @Test
    fun callbackWithMatchingStateExchangesTheCodeAndRestoresTheRoute() = runTest {
        signInFrom("/trees/t1")

        val form = endpoint.calls.single()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("code-1", form["code"])
        assertEquals("http://localhost:3000/", form["redirect_uri"])
        assertEquals(TEST_CLIENT, form["client_id"])
        assertEquals(43, form["code_verifier"]!!.length)
        assertNull(form["client_secret"])

        assertEquals(AuthState.SignedIn("user-1"), session.state.value)
        assertEquals("http://localhost:3000/#/trees/t1", bridge.url)
        assertNoSecretsInUrls()
        assertNull(bridge.storage[SessionManager.PENDING_KEY])
        // Only the signed-in flag is stored; tokens stay in memory.
        assertEquals(setOf(SessionManager.ACTIVE_KEY), bridge.storage.keys)
        assertFalse(bridge.storage.values.any { "access-1" in it || "refresh-1" in it })
        assertEquals(TokenResult.Token("access-1", 1), session.accessToken())
    }

    /**
     * A rejected callback never exchanges the code, strips it, and opens the tree
     * list. [callbackUrl] gets the genuine pending state.
     */
    private suspend fun assertRejectedCallback(callbackUrl: (String) -> String) {
        bridge.url = "http://localhost:3000/#/trees/t1"
        session.signIn("/trees/t1")
        bridge.url = callbackUrl(pending().state)
        session.start()

        assertTrue(endpoint.calls.isEmpty(), "no token request")
        assertEquals(AuthState.SignedOut(SignedOutReason.SIGN_IN_NOT_VERIFIED), session.state.value)
        assertEquals("http://localhost:3000/#/", bridge.url)
        assertNoSecretsInUrls()
        assertNull(bridge.storage[SessionManager.PENDING_KEY], "the one-time request is consumed")
        assertEquals(TokenResult.Expired, session.accessToken())
    }

    @Test
    fun callbackWithoutStateIsRejected() = runTest {
        assertRejectedCallback { "http://localhost:3000/#code=code-1&session_state=x" }
    }

    @Test
    fun callbackWithAnotherStateIsRejected() = runTest {
        assertRejectedCallback { "http://localhost:3000/#state=forged&code=code-1" }
    }

    @Test
    fun callbackRepeatingTheGenuineStateIsRejected() = runTest {
        assertRejectedCallback { state -> "http://localhost:3000/#state=$state&state=$state&code=code-1" }
    }

    @Test
    fun callbackWithTwoCodesIsRejected() = runTest {
        assertRejectedCallback { state -> "http://localhost:3000/#state=$state&code=a&code=b" }
    }

    @Test
    fun callbackInTheQueryStringIsRejectedAndStripped() = runTest {
        bridge.url = "http://localhost:3000/#/"
        session.signIn("/")
        val state = pending().state
        bridge.url = "http://localhost:3000/?state=$state&code=code-1#/trees/t1"
        session.start()

        assertTrue(endpoint.calls.isEmpty())
        assertEquals(AuthState.SignedOut(SignedOutReason.SIGN_IN_NOT_VERIFIED), session.state.value)
        assertEquals("http://localhost:3000/#/", bridge.url)
        assertNoSecretsInUrls()
    }

    @Test
    fun replayedCallbackFindsNoPendingRequest() = runTest {
        signInFrom()
        val second = newSession()
        bridge.url = "http://localhost:3000/#state=anything&code=code-1"
        second.start()

        assertEquals(1, endpoint.calls.size)
        assertEquals(AuthState.SignedOut(SignedOutReason.SIGN_IN_NOT_VERIFIED), second.state.value)
    }

    /** Completes a sign-in whose ID token is built from [payload] (given the request nonce). */
    private suspend fun signInWithIdToken(payload: (String) -> String) {
        session.signIn("/")
        val request = pending()
        endpoint.responses += HttpStatusCode.OK to tokens("access-1", idTokenFor(request, payload))
        bridge.url = "http://localhost:3000/#state=${request.state}&code=code-1"
        session.start()
    }

    @Test
    fun idTokenFailingOidcChecksIsRejected() = runTest {
        val cases = listOf<(String) -> String>(
            { idTokenPayload(nonce = "other") },
            { idTokenPayload(nonce = null) },
            { idTokenPayload(nonce = it, iss = "http://localhost:8081/realms/other") },
            { idTokenPayload(nonce = it, aud = "\"family-tree-server\"") },
            { idTokenPayload(nonce = it, exp = TEST_NOW - 3600) },
        )
        for (payload in cases) {
            signInWithIdToken(payload)
            assertEquals(AuthState.SignedOut(SignedOutReason.SIGN_IN_NOT_VERIFIED), session.state.value)
            assertEquals(TokenResult.Expired, session.accessToken())
            assertNull(bridge.storage[SessionManager.ACTIVE_KEY])
        }
    }

    @Test
    fun unusableOrFailedCodeExchangeIsASignInFailure() = runTest {
        val bodies = listOf(
            HttpStatusCode.BadRequest to """{"error":"invalid_grant"}""",
            HttpStatusCode.OK to """{"access_token":"","token_type":"Bearer","expires_in":300}""",
            HttpStatusCode.OK to """{"access_token":"a","token_type":"Bearer","expires_in":0}""",
            HttpStatusCode.OK to "not json",
        )
        for (reply in bodies) {
            session.signIn("/")
            val request = pending()
            endpoint.responses += reply
            bridge.url = "http://localhost:3000/#state=${request.state}&code=code-1"
            session.start()
            assertEquals(AuthState.SignedOut(SignedOutReason.SIGN_IN_FAILED), session.state.value, reply.second)
        }
    }

    @Test
    fun blockedCryptoOrStorageShowsARecoverableError() = runTest {
        bridge.cryptoBlocked = true
        session.signIn("/")
        assertEquals(AuthState.SignedOut(SignedOutReason.BROWSER_UNSUPPORTED), session.state.value)
        assertTrue(bridge.navigations.isEmpty())
        assertNull(bridge.storage[SessionManager.PENDING_KEY])

        bridge.cryptoBlocked = false
        bridge.storageBlocked = true
        session.signIn("/")
        assertEquals(AuthState.SignedOut(SignedOutReason.BROWSER_UNSUPPORTED), session.state.value)
        assertTrue(bridge.navigations.isEmpty())

        bridge.url = "http://localhost:3000/#state=s&code=code-1"
        session.start()
        assertEquals(AuthState.SignedOut(SignedOutReason.BROWSER_UNSUPPORTED), session.state.value)
        assertNoSecretsInUrls()
        assertTrue(endpoint.calls.isEmpty())
    }

    @Test
    fun reloadWhileSignedInAsksKeycloakSilentlyForTheSameRoute() = runTest {
        bridge.storage[SessionManager.ACTIVE_KEY] = "1"
        bridge.url = "http://localhost:3000/#/trees/t1?view=ancestors"
        session.start()

        val sent = UrlParts(bridge.navigations.single())
        assertEquals("none", sent.queryParameters["prompt"])
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
        bridge.url = "http://localhost:3000/#error=login_required&state=${request.state}"
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
        endpoint.responses += HttpStatusCode.OK to tokens("access-2", refresh = "refresh-2")

        assertEquals(TokenResult.Token("access-2", 1), session.accessToken())
        val form = endpoint.calls.last()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("refresh-1", form["refresh_token"])
        assertEquals(TokenResult.Token("access-2", 1), session.accessToken())
        assertEquals(2, endpoint.calls.size)
    }

    @Test
    fun shortLifetimeRefreshesHalfwayInsteadOfImmediately() = runTest {
        signInFrom(expiresIn = 1)

        now = 400
        assertEquals(TokenResult.Token("access-1", 1), session.accessToken())
        assertEquals(1, endpoint.calls.size, "no refresh while more than half the lifetime is left")

        now = 600
        endpoint.responses += HttpStatusCode.OK to tokens("access-2", expiresIn = 1)
        assertEquals(TokenResult.Token("access-2", 1), session.accessToken())
    }

    @Test
    fun refreshedIdTokenForAnotherSubjectEndsTheSession() = runTest {
        signInFrom()
        endpoint.responses += HttpStatusCode.OK to tokens("access-2", testJwt(idTokenPayload(sub = "user-2", nonce = null)))

        assertEquals(TokenResult.Expired, session.onRejected(TokenResult.Token("access-1", 1)))
        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED), session.state.value)
    }

    @Test
    fun unusableRefreshResponseEndsTheSession() = runTest {
        signInFrom()
        endpoint.responses += HttpStatusCode.OK to """{"access_token":" ","token_type":"Bearer","expires_in":300}"""

        assertEquals(TokenResult.Expired, session.onRejected(TokenResult.Token("access-1", 1)))
        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED), session.state.value)
    }

    /** Starts a refresh, ends the session while the token request is in flight, then lets it finish. */
    private suspend fun kotlinx.coroutines.test.TestScope.refreshInFlightThen(endSession: () -> Unit): TokenResult {
        signInFrom()
        endpoint.gate = CompletableDeferred()
        endpoint.responses += HttpStatusCode.OK to tokens("access-2", refresh = "refresh-2")
        val refresh = async { session.onRejected(TokenResult.Token("access-1", 1)) }
        endpoint.entered.await()
        endSession()
        endpoint.gate!!.complete(Unit)
        return refresh.await()
    }

    @Test
    fun refreshFinishingAfterSignOutDoesNotRestoreTheSession() = runTest {
        val result = refreshInFlightThen { session.signOut() }

        assertEquals(TokenResult.Expired, result)
        assertEquals(AuthState.SignedOut(), session.state.value)
        assertEquals(TokenResult.Expired, session.accessToken())
        assertTrue(bridge.storage.isEmpty())
    }

    @Test
    fun refreshFinishingAfterExpiryDoesNotRestoreTheSession() = runTest {
        val result = refreshInFlightThen { session.endSession(TokenResult.Token("access-1", 1)) }

        assertEquals(TokenResult.Expired, result)
        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED), session.state.value)
        assertEquals(TokenResult.Expired, session.accessToken())
        assertNull(bridge.storage[SessionManager.ACTIVE_KEY])
    }

    @Test
    fun malformedCallbackIsRemovedBeforeAnyTokenRequest() = runTest {
        session.signIn("/")
        bridge.url = "http://localhost:3000/#state=%GG&code=code-1"
        session.start()

        assertTrue(endpoint.calls.isEmpty())
        assertEquals(AuthState.SignedOut(SignedOutReason.SIGN_IN_NOT_VERIFIED), session.state.value)
        assertNull(bridge.storage[SessionManager.PENDING_KEY])
        assertNoSecretsInUrls()
    }

    @Test
    fun staleUnauthorizedResponseCannotUseOrExpireAReplacementSession() = runTest {
        signInFrom()
        val stale = assertIs<TokenResult.Token>(session.accessToken())
        session.signOut()
        signInFrom()
        val current = assertIs<TokenResult.Token>(session.accessToken())
        val exchanges = endpoint.calls.size

        assertEquals(TokenResult.Expired, session.onRejected(stale))
        session.endSession(stale)
        assertEquals(current, session.accessToken())
        assertEquals(exchanges, endpoint.calls.size)
    }

    @Test
    fun rejectedRefreshEndsTheSession() = runTest {
        signInFrom()
        endpoint.responses += HttpStatusCode.BadRequest to """{"error":"invalid_grant"}"""

        assertEquals(TokenResult.Expired, session.onRejected(TokenResult.Token("access-1", 1)))
        assertEquals(AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED), session.state.value)
        assertNull(bridge.storage[SessionManager.ACTIVE_KEY])
        assertEquals(TokenResult.Expired, session.accessToken())
    }

    @Test
    fun unreachableKeycloakKeepsTheSession() = runTest {
        signInFrom()
        endpoint.responses += HttpStatusCode.ServiceUnavailable to ""

        assertEquals(TokenResult.Unavailable, session.onRejected(TokenResult.Token("access-1", 1)))
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
        val logout = UrlParts(bridge.navigations.last())
        assertEquals(config.endSessionEndpoint, logout.base)
        assertEquals(TEST_CLIENT, logout.queryParameters["client_id"])
        assertTrue(logout.queryParameters["id_token_hint"]!!.isNotEmpty())
        assertNull(logout.queryParameters["post_logout_redirect_uri"])
    }
}
