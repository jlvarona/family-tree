package com.moxietech.family_tree.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.URLDecodeException
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/** What the UI needs to know about the sign-in; never carries tokens. */
sealed interface AuthState {
    /** Reading the URL: a callback to finish, or a session to restore. */
    data object Starting : AuthState

    /** The browser is being sent to Keycloak. */
    data object Redirecting : AuthState

    data class SignedOut(val reason: SignedOutReason? = null) : AuthState

    /** [subject] is the ID token's `sub`, used only to tell sessions apart. */
    data class SignedIn(val subject: String) : AuthState
}

enum class SignedOutReason {
    /** The refresh token or Keycloak session ended, or the API stopped accepting the token. */
    SESSION_EXPIRED,

    /** Keycloak returned an error, or the code exchange failed or returned unusable tokens. */
    SIGN_IN_FAILED,

    /** The callback's `state` was missing, repeated or wrong, or the ID token failed its checks. */
    SIGN_IN_NOT_VERIFIED,

    /** The browser refused sessionStorage or Web Crypto, so sign-in can't run safely. */
    BROWSER_UNSUPPORTED,
}

sealed interface TokenResult {
    data class Token(val value: String, val sessionEpoch: Long) : TokenResult

    /** The session has ended; [SessionManager.state] is now [AuthState.SignedOut]. */
    data object Expired : TokenResult

    /** Keycloak couldn't be reached; the session is kept and the call can be retried. */
    data object Unavailable : TokenResult
}

/** What the API client needs from the session. */
interface AccessTokenSource {
    suspend fun accessToken(): TokenResult

    /** The API answered `401` to [rejected]: refresh once, or end the session. */
    suspend fun onRejected(rejected: TokenResult.Token): TokenResult

    /** The API rejected a freshly refreshed token too: end the session. */
    fun endSession(rejected: TokenResult.Token)
}

/** What the app controller needs from the sign-in. */
interface AuthSession {
    val state: StateFlow<AuthState>

    /** Finishes a sign-in callback, restores a session after reload, or settles on signed out. */
    suspend fun start()

    /** Sends the browser to sign in; [returnRoute] (a URL fragment) is restored afterwards. */
    suspend fun signIn(returnRoute: String)

    fun signOut()
}

/**
 * Browser sign-in against Keycloak with the authorization code flow and PKCE
 * (S256), for the public `family-tree-web` client.
 *
 * - **Request.** [signIn] takes `state`, `nonce` and the code verifier from Web
 *   Crypto randomness and hashes the verifier with Web Crypto. It stores them,
 *   with the route to return to, as one pending record in sessionStorage, then
 *   navigates to Keycloak with `response_mode=fragment`, so the code comes back
 *   in the URL fragment and never reaches a server log or `Referer`.
 * - **Callback.** [start] consumes the pending record once and immediately
 *   replaces the URL, removing every callback parameter. The code is exchanged
 *   only when exactly one `state` matches the record. Anything else (no state,
 *   a wrong or repeated one, a response in the query string) returns to the
 *   tree list with a "couldn't be verified" message.
 * - **Tokens** must be a usable Bearer response (RFC 6749 §5.1). The ID token's
 *   issuer, audience/`azp`, expiry and nonce are checked before the session
 *   exists (OIDC Core §3.1.3.7); a refreshed ID token must also keep the same
 *   subject (§12.2). Tokens live in memory only. A sessionStorage flag lets a
 *   reload ask Keycloak again with `prompt=none`; `login_required` there means
 *   the Keycloak session has ended.
 * - **Expiry.** The access token is refreshed before it expires (in the
 *   background, before calls, and once after an API `401`). After every token
 *   request returns, the response is used only if the same session is still
 *   current, so a sign-out or expiry during the request can't bring the
 *   credentials back.
 * - **Sign-out** clears everything locally first, then ends the Keycloak
 *   session at its end-session endpoint with `id_token_hint`.
 *
 * Nothing here logs tokens, codes or claims.
 */
@OptIn(ExperimentalTime::class)
class SessionManager(
    private val config: WebAppConfig,
    private val bridge: BrowserBridge,
    private val http: HttpClient,
    private val scope: CoroutineScope,
    private val clock: () -> Long = monotonicMillis(),
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : AccessTokenSource, AuthSession {

    private val _state = MutableStateFlow<AuthState>(AuthState.Starting)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    private var session: Session? = null
    private var refreshJob: Job? = null
    private val refreshLock = Mutex()

    /** Bumped whenever a sign-in starts or a session ends, so late responses can tell they're stale. */
    private var epoch = 0L

    override suspend fun start() {
        val url = UrlParts(bridge.currentUrl())
        val callback = try {
            callbackOf(url)
        } catch (e: URLDecodeException) {
            bridge.sessionRemove(PENDING_KEY)
            bridge.replaceUrl(url.base + "#/")
            fail(SignedOutReason.SIGN_IN_NOT_VERIFIED)
            return
        }
        try {
            when {
                callback != null -> completeCallback(url, callback)
                bridge.sessionGet(ACTIVE_KEY) == ACTIVE_VALUE ->
                    authorize(returnRoute = if (url.fragmentIsRoute) url.fragment else "", silent = true)
                else -> _state.value = AuthState.SignedOut()
            }
        } catch (e: BrowserFeatureException) {
            fail(SignedOutReason.BROWSER_UNSUPPORTED)
        }
    }

    override suspend fun signIn(returnRoute: String) = authorize(returnRoute, silent = false)

    /**
     * Forgets the session in this tab, then ends the Keycloak session. Keycloak
     * shows its own signed-out page, because the realm lists no post-logout
     * redirect URI for `family-tree-web`.
     */
    override fun signOut() {
        val idToken = session?.idToken
        clearSession()
        bridge.sessionRemove(PENDING_KEY)
        _state.value = AuthState.SignedOut()
        if (idToken != null) {
            bridge.navigate(
                URLBuilder(config.endSessionEndpoint).apply {
                    parameters.append("client_id", config.clientId)
                    parameters.append("id_token_hint", idToken)
                }.buildString(),
            )
        }
    }

    override suspend fun accessToken(): TokenResult {
        val current = session ?: return TokenResult.Expired
        if (clock() < current.refreshAt) return current.credential()
        val refreshed = refresh(seen = current)
        // Keycloak unreachable but the token hasn't expired yet: keep using it.
        if (refreshed == TokenResult.Unavailable && session === current && clock() < current.accessExpiresAt) {
            return current.credential()
        }
        return refreshed
    }

    override suspend fun onRejected(rejected: TokenResult.Token): TokenResult {
        val current = session ?: return TokenResult.Expired
        if (current.epoch != rejected.sessionEpoch) return TokenResult.Expired
        if (current.accessToken != rejected.value) return current.credential()
        return refresh(seen = current)
    }

    override fun endSession(rejected: TokenResult.Token) {
        if (session?.credential() == rejected) expire()
    }

    private suspend fun authorize(returnRoute: String, silent: Boolean) {
        val requestEpoch = ++epoch
        try {
            val verifier = Pkce.randomToken(bridge, Pkce.VERIFIER_BYTES)
            val pending = PendingAuthorization(
                state = Pkce.randomToken(bridge, Pkce.STATE_BYTES),
                codeVerifier = verifier,
                nonce = Pkce.randomToken(bridge, Pkce.STATE_BYTES),
                redirectUri = UrlParts(bridge.currentUrl()).base,
                returnRoute = returnRoute,
                silent = silent,
            )
            val challenge = Pkce.s256Challenge(bridge, verifier)
            if (epoch != requestEpoch) return
            bridge.sessionSet(PENDING_KEY, json.encodeToString(PendingAuthorization.serializer(), pending))
            val url = URLBuilder(config.authorizationEndpoint).apply {
                parameters.append("response_type", "code")
                parameters.append("response_mode", "fragment")
                parameters.append("client_id", config.clientId)
                parameters.append("redirect_uri", pending.redirectUri)
                parameters.append("scope", "openid")
                parameters.append("state", pending.state)
                parameters.append("nonce", pending.nonce)
                parameters.append("code_challenge", challenge)
                parameters.append("code_challenge_method", "S256")
                if (silent) parameters.append("prompt", "none")
            }.buildString()
            _state.value = AuthState.Redirecting
            bridge.navigate(url)
        } catch (e: CancellationException) {
            if (epoch == requestEpoch) bridge.sessionRemove(PENDING_KEY)
            throw e
        } catch (e: BrowserFeatureException) {
            if (epoch == requestEpoch) {
                bridge.sessionRemove(PENDING_KEY)
                fail(SignedOutReason.BROWSER_UNSUPPORTED)
            }
        }
    }

    private suspend fun completeCallback(url: UrlParts, callback: Callback) {
        // One-time use: a replayed or forged callback finds nothing to match.
        val pending = try {
            bridge.sessionGet(PENDING_KEY)?.let(::decodePending)
        } finally {
            bridge.sessionRemove(PENDING_KEY)
            // Whatever happens next, the code and state leave the address bar first.
            bridge.replaceUrl(url.base + "#/")
        }
        val params = callback.parameters
        val states = params.getAll("state").orEmpty()
        val error = params.getAll("error").orEmpty()
        val code = params.getAll("code").orEmpty().singleOrNull()?.takeIf { it.isNotEmpty() }
        // Exactly one matching state, and exactly one code or one error.
        val wellFormed = pending != null && !callback.inQuery && states.size == 1 &&
            constantTimeEquals(pending.state, states.single()) &&
            (if (error.isEmpty()) code != null else error.size == 1)
        if (pending == null || !wellFormed) {
            fail(SignedOutReason.SIGN_IN_NOT_VERIFIED)
            return
        }
        // Only a verified response restores the route it was started from.
        if (pending.returnRoute.isNotEmpty()) bridge.replaceUrl(url.base + "#" + pending.returnRoute)

        if (error.isNotEmpty()) {
            val ended = pending.silent && error.single() in SILENT_SIGN_IN_REQUIRED
            fail(if (ended) SignedOutReason.SESSION_EXPIRED else SignedOutReason.SIGN_IN_FAILED)
            return
        }
        if (code == null) return fail(SignedOutReason.SIGN_IN_NOT_VERIFIED)

        val epochAtRequest = epoch
        val result = requestTokens(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to pending.redirectUri,
            "client_id" to config.clientId,
            "code_verifier" to pending.codeVerifier,
        )
        // Signed out or restarted while the exchange ran: drop the response.
        if (epoch != epochAtRequest) return
        val response = (result as? TokenCall.Success)?.response
        if (response == null) {
            fail(SignedOutReason.SIGN_IN_FAILED)
            return
        }
        val claims = response.idToken?.let(IdTokens::parse)
        if (claims == null || !IdTokens.isValid(claims, config, nowSeconds(), pending.nonce, requireNonce = true)) {
            fail(SignedOutReason.SIGN_IN_NOT_VERIFIED)
            return
        }
        install(response, previous = null, subject = claims.subject, nonce = pending.nonce)
        try {
            bridge.sessionSet(ACTIVE_KEY, ACTIVE_VALUE)
        } catch (e: BrowserFeatureException) {
            // Signed in for this page; a reload just won't restore the session.
        }
        _state.value = AuthState.SignedIn(claims.subject)
    }

    /** Exchanges the refresh token of [seen]; one refresh at a time. */
    private suspend fun refresh(seen: Session): TokenResult = refreshLock.withLock {
        val current = session ?: return@withLock TokenResult.Expired
        if (current.epoch != seen.epoch) return@withLock TokenResult.Expired
        // Another caller refreshed while this one waited for the lock.
        if (current !== seen) return@withLock current.credential()
        val refreshToken = current.refreshToken
        val refreshExpired = current.refreshExpiresAt?.let { clock() >= it } ?: false
        if (refreshToken == null || refreshExpired) {
            expire()
            return@withLock TokenResult.Expired
        }
        val result = requestTokens(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to config.clientId,
        )
        // Signed out, expired or replaced while the request ran: never install its tokens.
        if (session !== current) return@withLock TokenResult.Expired
        when (result) {
            is TokenCall.Success -> {
                val idToken = result.response.idToken
                val idOk = idToken == null || IdTokens.parse(idToken)?.let {
                    IdTokens.isValid(it, config, nowSeconds(), current.nonce, requireNonce = false, current.subject)
                } == true
                if (idOk) {
                    install(result.response, previous = current, subject = current.subject, nonce = current.nonce)
                    TokenResult.Token(result.response.accessToken, current.epoch)
                } else {
                    expire()
                    TokenResult.Expired
                }
            }
            TokenCall.Rejected -> {
                expire()
                TokenResult.Expired
            }
            TokenCall.Unavailable -> TokenResult.Unavailable
        }
    }

    private fun install(response: TokenResponse, previous: Session?, subject: String, nonce: String) {
        val now = clock()
        val lifetime = response.expiresIn * 1000
        // Refresh ahead of expiry, but for very short lifetimes no earlier than halfway.
        val lead = minOf(REFRESH_SKEW_MS, lifetime / 2)
        val next = Session(
            epoch = previous?.epoch ?: epoch,
            accessToken = response.accessToken,
            accessExpiresAt = now + lifetime,
            refreshAt = now + lifetime - lead,
            refreshToken = response.refreshToken ?: previous?.refreshToken,
            // Keycloak reports 0 for tokens without an idle limit.
            refreshExpiresAt = when {
                response.refreshExpiresIn != null && response.refreshExpiresIn > 0 -> now + response.refreshExpiresIn * 1000
                response.refreshToken == null -> previous?.refreshExpiresAt
                else -> null
            },
            idToken = response.idToken ?: previous?.idToken.orEmpty(),
            subject = subject,
            nonce = nonce,
        )
        session = next
        scheduleRefresh(next)
    }

    /** Refreshes ahead of expiry so an idle tab notices an ended session and drops its data. */
    private fun scheduleRefresh(target: Session) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            delay((target.refreshAt - clock()).coerceAtLeast(MIN_REFRESH_DELAY_MS))
            while (session === target) {
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
        epoch++
        session = null
        refreshJob?.cancel()
        refreshJob = null
        bridge.sessionRemove(ACTIVE_KEY)
    }

    private suspend fun requestTokens(vararg form: Pair<String, String>): TokenCall {
        val response = try {
            http.submitForm(
                url = config.tokenEndpoint,
                formParameters = parameters { form.forEach { (name, value) -> append(name, value) } },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Network failure. The cause isn't logged: it may quote the request.
            return TokenCall.Unavailable
        }
        val status = response.status.value
        return when {
            status in 200..299 -> {
                val decoded = try {
                    json.decodeFromString(TokenResponse.serializer(), response.bodyAsText())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (decoded != null && decoded.isUsable()) TokenCall.Success(decoded) else TokenCall.Rejected
            }
            status == 400 || status == 401 -> TokenCall.Rejected
            else -> TokenCall.Unavailable
        }
    }

    private fun decodePending(text: String): PendingAuthorization? =
        try {
            json.decodeFromString(PendingAuthorization.serializer(), text)
        } catch (e: Exception) {
            null
        }

    private class Session(
        val epoch: Long,
        val accessToken: String,
        val accessExpiresAt: Long,
        val refreshAt: Long,
        val refreshToken: String?,
        val refreshExpiresAt: Long?,
        val idToken: String,
        val subject: String,
        val nonce: String,
    ) {
        fun credential() = TokenResult.Token(accessToken, epoch)
    }

    private sealed interface TokenCall {
        class Success(val response: TokenResponse) : TokenCall

        /** Refused (400/401) or a 2xx without usable tokens. */
        data object Rejected : TokenCall

        data object Unavailable : TokenCall
    }

    /** Authorization response parameters, from the fragment (requested) or the query (never requested). */
    internal class Callback(val parameters: Parameters, val inQuery: Boolean)

    companion object {
        internal const val PENDING_KEY = "family-tree.oidc.pending"
        internal const val ACTIVE_KEY = "family-tree.oidc.active"
        private const val ACTIVE_VALUE = "1"

        /** Refresh this long before the access token's expiry (or halfway, for shorter lifetimes). */
        internal const val REFRESH_SKEW_MS = 30_000L
        private const val MIN_REFRESH_DELAY_MS = 1_000L
        private const val RETRY_DELAY_MS = 30_000L

        private val CALLBACK_KEYS = listOf("code", "state", "error")

        /** `prompt=none` errors meaning "the Keycloak session is gone, sign in again". */
        private val SILENT_SIGN_IN_REQUIRED = setOf(
            "login_required", "interaction_required", "consent_required", "account_selection_required",
        )

        private val json = Json { ignoreUnknownKeys = true }

        internal fun callbackOf(url: UrlParts): Callback? {
            if (!url.fragmentIsRoute) {
                val fragment = url.fragmentParameters
                if (CALLBACK_KEYS.any { it in fragment.names() }) return Callback(fragment, inQuery = false)
            }
            val query = url.queryParameters
            return if (CALLBACK_KEYS.any { it in query.names() }) Callback(query, inQuery = true) else null
        }

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
