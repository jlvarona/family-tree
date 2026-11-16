package com.moxietech.family_tree.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.time.TimeSource

/** What the UI needs to know about the sign-in; never carries tokens. */
sealed interface AuthState {
    /** Reading the URL: a callback to finish, or a session to restore. */
    data object Starting : AuthState

    /** The browser is being sent to Keycloak. */
    data object Redirecting : AuthState

    data class SignedOut(val reason: SignedOutReason? = null) : AuthState

    /** [subject] is the token's `sub`, used only to tell sessions apart. */
    data class SignedIn(val subject: String) : AuthState
}

enum class SignedOutReason {
    /** The refresh token or Keycloak session ended, or the API stopped accepting the token. */
    SESSION_EXPIRED,

    /** Keycloak returned an error or the code could not be exchanged. */
    SIGN_IN_FAILED,

    /** The callback's `state` (or the ID token's nonce) didn't match this tab's request. */
    SIGN_IN_NOT_VERIFIED,
}

sealed interface TokenResult {
    data class Token(val value: String) : TokenResult

    /** The session has ended; [SessionManager.state] is now [AuthState.SignedOut]. */
    data object Expired : TokenResult

    /** Keycloak couldn't be reached; the session is kept and the call can be retried. */
    data object Unavailable : TokenResult
}

/** What the API client needs from the session. */
interface AccessTokenSource {
    suspend fun accessToken(): TokenResult

    /** The API answered `401` to [rejected]: refresh once, or end the session. */
    suspend fun onRejected(rejected: String): TokenResult

    /** The API rejected a freshly refreshed token too: end the session. */
    fun endSession()
}

/** What the app controller needs from the sign-in. */
interface AuthSession {
    val state: StateFlow<AuthState>

    /** Finishes a sign-in callback, restores a session after reload, or settles on signed out. */
    suspend fun start()

    /** Sends the browser to sign in; [returnRoute] (a URL fragment) is restored afterwards. */
    fun signIn(returnRoute: String)

    fun signOut()
}

/**
 * Browser sign-in against Keycloak with the authorization code flow and PKCE
 * (S256), for the public `family-tree-web` client.
 *
 * - **Request.** [signIn] generates `state`, `nonce` and the code verifier from
 *   Web Crypto randomness, stores them with the route to return to as one
 *   pending record in sessionStorage, and navigates to Keycloak.
 * - **Callback.** [start] consumes the pending record once, removes the code
 *   and state from the address bar and history entry, and exchanges the code
 *   only if `state` matches. The ID token's `nonce` must match too.
 * - **Tokens** live in memory only. sessionStorage keeps a flag that this tab
 *   is signed in; after a reload [start] sees it and asks Keycloak again with
 *   `prompt=none`, which returns a new code while the Keycloak session is alive
 *   and `login_required` once it has ended (shown as an expired session).
 * - **Expiry.** The access token is refreshed shortly before it expires, in the
 *   background and before each API call, and once after an API `401`. When the
 *   refresh token is rejected the session ends and [state] becomes
 *   `SignedOut(SESSION_EXPIRED)`; observers drop all user data on that.
 * - **Sign-out** clears everything locally first, then ends the Keycloak
 *   session at its end-session endpoint with `id_token_hint`.
 *
 * Nothing here logs tokens, codes or claims.
 */
class SessionManager(
    private val config: WebAppConfig,
    private val bridge: BrowserBridge,
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = monotonicMillis(),
) : AccessTokenSource, AuthSession {

    private val _state = MutableStateFlow<AuthState>(AuthState.Starting)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private var tokens: Tokens? = null
    private var refreshJob: Job? = null
    private val refreshLock = Mutex()

    override suspend fun start() {
        val url = ParsedUrl.parse(bridge.currentUrl())
        val query = url.query
        when {
            STATE_PARAM in query && (CODE_PARAM in query || ERROR_PARAM in query) -> completeCallback(url)
            bridge.sessionGet(ACTIVE_KEY) == ACTIVE_VALUE -> authorize(returnRoute = url.fragment, silent = true)
            else -> _state.value = AuthState.SignedOut()
        }
    }

    override fun signIn(returnRoute: String) = authorize(returnRoute, silent = false)

    /**
     * Forgets the session in this tab, then ends the Keycloak session. Keycloak
     * shows its own signed-out page, because the realm lists no post-logout
     * redirect URI for `family-tree-web`.
     */
    override fun signOut() {
        val idToken = tokens?.idToken
        clearSession()
        bridge.sessionRemove(PENDING_KEY)
        _state.value = AuthState.SignedOut()
        if (idToken != null) {
            bridge.navigate(
                config.endSessionEndpoint + "?" + UrlCodec.query(
                    listOf("client_id" to config.clientId, "id_token_hint" to idToken),
                ),
            )
        }
    }

    override suspend fun accessToken(): TokenResult {
        val current = tokens ?: return TokenResult.Expired
        if (clock() < current.accessExpiresAt - REFRESH_SKEW_MS) return TokenResult.Token(current.accessToken)
        return refresh(seen = current)
    }

    override suspend fun onRejected(rejected: String): TokenResult {
        val current = tokens ?: return TokenResult.Expired
        if (current.accessToken != rejected) return TokenResult.Token(current.accessToken)
        return refresh(seen = current)
    }

    override fun endSession() {
        if (tokens != null) expire()
    }

    private fun authorize(returnRoute: String, silent: Boolean) {
        val pending = PendingAuthorization(
            state = Pkce.randomToken(bridge, Pkce.STATE_BYTES),
            codeVerifier = Pkce.randomToken(bridge, Pkce.VERIFIER_BYTES),
            nonce = Pkce.randomToken(bridge, Pkce.STATE_BYTES),
            redirectUri = ParsedUrl.parse(bridge.currentUrl()).base,
            returnRoute = returnRoute,
            silent = silent,
        )
        bridge.sessionSet(PENDING_KEY, json.encodeToString(PendingAuthorization.serializer(), pending))
        val params = buildList {
            add("response_type" to "code")
            add("client_id" to config.clientId)
            add("redirect_uri" to pending.redirectUri)
            add("scope" to "openid")
            add("state" to pending.state)
            add("nonce" to pending.nonce)
            add("code_challenge" to Pkce.s256Challenge(pending.codeVerifier))
            add("code_challenge_method" to "S256")
            if (silent) add("prompt" to "none")
        }
        _state.value = AuthState.Redirecting
        bridge.navigate(config.authorizationEndpoint + "?" + UrlCodec.query(params))
    }

    private suspend fun completeCallback(url: ParsedUrl) {
        // One-time use: a replayed or forged callback finds nothing to match.
        val pending = bridge.sessionGet(PENDING_KEY)?.let { decodePending(it) }
        bridge.sessionRemove(PENDING_KEY)
        // Keep the code and state out of the address bar and this history entry.
        val route = pending?.returnRoute.orEmpty()
        bridge.replaceUrl(url.base + if (route.isEmpty()) "" else "#$route")

        val query = url.query
        if (pending == null || !constantTimeEquals(pending.state, query.getValue(STATE_PARAM))) {
            fail(SignedOutReason.SIGN_IN_NOT_VERIFIED)
            return
        }
        val error = query[ERROR_PARAM]
        if (error != null) {
            val ended = pending.silent && error in SILENT_SIGN_IN_REQUIRED
            fail(if (ended) SignedOutReason.SESSION_EXPIRED else SignedOutReason.SIGN_IN_FAILED)
            return
        }
        val response = when (
            val result = requestTokens(
                "grant_type" to "authorization_code",
                "code" to query.getValue(CODE_PARAM),
                "redirect_uri" to pending.redirectUri,
                "client_id" to config.clientId,
                "code_verifier" to pending.codeVerifier,
            )
        ) {
            is TokenCall.Success -> result.response
            else -> {
                fail(SignedOutReason.SIGN_IN_FAILED)
                return
            }
        }
        val idClaims = response.idToken?.let(::decodeJwtClaims)
        val nonce = idClaims?.get("nonce")
        val subject = idClaims?.get("sub")
        if (nonce == null || subject == null || !constantTimeEquals(pending.nonce, nonce)) {
            fail(SignedOutReason.SIGN_IN_NOT_VERIFIED)
            return
        }
        if (!response.tokenType.equals("Bearer", ignoreCase = true)) {
            fail(SignedOutReason.SIGN_IN_FAILED)
            return
        }
        install(response, previous = null, subject = subject)
        bridge.sessionSet(ACTIVE_KEY, ACTIVE_VALUE)
        _state.value = AuthState.SignedIn(subject)
    }

    /** Exchanges the refresh token for [seen]; one refresh at a time. */
    private suspend fun refresh(seen: Tokens): TokenResult = refreshLock.withLock {
        val current = tokens ?: return@withLock TokenResult.Expired
        // Another caller refreshed while this one waited for the lock.
        if (current !== seen) return@withLock TokenResult.Token(current.accessToken)
        val refreshToken = current.refreshToken
        val refreshExpired = current.refreshExpiresAt?.let { clock() >= it } ?: false
        if (refreshToken == null || refreshExpired) {
            expire()
            return@withLock TokenResult.Expired
        }
        when (
            val result = requestTokens(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to config.clientId,
            )
        ) {
            is TokenCall.Success -> {
                install(result.response, previous = current, subject = current.subject)
                TokenResult.Token(result.response.accessToken)
            }
            TokenCall.Rejected -> {
                expire()
                TokenResult.Expired
            }
            TokenCall.Unavailable -> TokenResult.Unavailable
        }
    }

    private fun install(response: TokenResponse, previous: Tokens?, subject: String) {
        val now = clock()
        val next = Tokens(
            accessToken = response.accessToken,
            accessExpiresAt = now + response.expiresIn * 1000,
            refreshToken = response.refreshToken ?: previous?.refreshToken,
            // Keycloak reports 0 for tokens without an idle limit.
            refreshExpiresAt = response.refreshExpiresIn?.takeIf { it > 0 }?.let { now + it * 1000 }
                ?: previous?.refreshExpiresAt.takeIf { response.refreshToken == null },
            idToken = response.idToken ?: previous?.idToken,
            subject = subject,
        )
        tokens = next
        scheduleRefresh(next)
    }

    /** Refreshes ahead of expiry so an idle tab notices an ended session and drops its data. */
    private fun scheduleRefresh(target: Tokens) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay((target.accessExpiresAt - REFRESH_SKEW_MS - clock()).coerceAtLeast(0))
            while (tokens === target) {
                if (refresh(seen = target) != TokenResult.Unavailable) break
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private fun expire() {
        clearSession()
        _state.value = AuthState.SignedOut(SignedOutReason.SESSION_EXPIRED)
    }

    private fun fail(reason: SignedOutReason) {
        clearSession()
        _state.value = AuthState.SignedOut(reason)
    }

    private fun clearSession() {
        tokens = null
        refreshJob?.cancel()
        refreshJob = null
        bridge.sessionRemove(ACTIVE_KEY)
    }

    private suspend fun requestTokens(vararg form: Pair<String, String>): TokenCall {
        return try {
            val response = http.submitForm(
                url = config.tokenEndpoint,
                formParameters = parameters { form.forEach { (name, value) -> append(name, value) } },
            )
            val status = response.status.value
            when {
                status in 200..299 -> TokenCall.Success(json.decodeFromString(TokenResponse.serializer(), response.bodyAsText()))
                status == 400 || status == 401 -> TokenCall.Rejected
                else -> TokenCall.Unavailable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Throwable) {
            // Network failure or an unreadable body. The cause isn't logged: it may quote the request.
            TokenCall.Unavailable
        }
    }

    private fun decodePending(text: String): PendingAuthorization? =
        try {
            json.decodeFromString(PendingAuthorization.serializer(), text)
        } catch (ignored: Exception) {
            null
        }

    private class Tokens(
        val accessToken: String,
        val accessExpiresAt: Long,
        val refreshToken: String?,
        val refreshExpiresAt: Long?,
        val idToken: String?,
        val subject: String,
    )

    private sealed interface TokenCall {
        class Success(val response: TokenResponse) : TokenCall
        data object Rejected : TokenCall
        data object Unavailable : TokenCall
    }

    companion object {
        internal const val PENDING_KEY = "family-tree.oidc.pending"
        internal const val ACTIVE_KEY = "family-tree.oidc.active"
        private const val ACTIVE_VALUE = "1"
        private const val STATE_PARAM = "state"
        private const val CODE_PARAM = "code"
        private const val ERROR_PARAM = "error"

        /** Refresh this long before the access token's `exp`. */
        internal const val REFRESH_SKEW_MS = 30_000L
        private const val RETRY_DELAY_MS = 30_000L

        /** `prompt=none` errors meaning "the Keycloak session is gone, sign in again". */
        private val SILENT_SIGN_IN_REQUIRED = setOf(
            "login_required", "interaction_required", "consent_required", "account_selection_required",
        )

        private val json = Json { ignoreUnknownKeys = true }

        fun monotonicMillis(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}

/** The one-time sign-in request kept in sessionStorage between the redirect and the callback. */
@Serializable
internal data class PendingAuthorization(
    val state: String,
    val codeVerifier: String,
    val nonce: String,
    val redirectUri: String,
    val returnRoute: String,
    val silent: Boolean,
)

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long? = null,
    @SerialName("id_token") val idToken: String? = null,
)

/**
 * Reads a JWT's string claims without verifying it. Used only on the ID token
 * just received from the token endpoint over TLS, to compare its `nonce` with
 * this tab's request and read `sub`; the API verifies access tokens itself.
 */
internal fun decodeJwtClaims(jwt: String): Map<String, String>? {
    val payload = jwt.split('.').getOrNull(1) ?: return null
    val bytes = Base64Url.decode(payload) ?: return null
    return try {
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            .mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content }
            }
            .toMap()
    } catch (ignored: Exception) {
        null
    }
}

internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
