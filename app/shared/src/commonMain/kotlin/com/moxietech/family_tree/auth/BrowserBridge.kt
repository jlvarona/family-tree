package com.moxietech.family_tree.auth

import io.ktor.http.Parameters
import io.ktor.http.parseQueryString

/**
 * The few browser facilities the web workflow needs, behind an interface so the
 * sign-in, routing and state code stays in common code and is unit tested with
 * a fake. The js and wasmJs targets implement it over `window` (webMain
 * `WindowBridge`).
 *
 * Only short-lived values go into sessionStorage (per tab, cleared when the tab
 * closes): the one-time sign-in request (state, PKCE verifier, nonce, return
 * route) and a flag that this tab was signed in. Tokens are kept in memory only.
 *
 * Storage and crypto methods throw [BrowserFeatureException] when the browser
 * refuses them (blocked site data, an insecure context); the session turns that
 * into a recoverable, localized sign-in error.
 */
interface BrowserBridge {
    /** `location.origin`, e.g. `http://localhost:3000`. */
    val origin: String

    /** `location.href`. */
    fun currentUrl(): String

    /** `history.replaceState` to [url] (same origin), without reloading. */
    fun replaceUrl(url: String)

    /** Full-page navigation to [url] (the identity provider). */
    fun navigate(url: String)

    /** Sets `location.hash` (without the leading `#`), adding a history entry. */
    fun setHash(hash: String)

    /** Calls [listener] whenever `location.hash` changes (back/forward, typed URL). */
    fun onHashChange(listener: () -> Unit)

    fun sessionGet(key: String): String?

    fun sessionSet(key: String, value: String)

    /** Never throws: removing is best effort, so clean-up paths can't fail. */
    fun sessionRemove(key: String)

    /** Cryptographically secure random bytes (`crypto.getRandomValues`). */
    fun randomBytes(count: Int): ByteArray

    /** SHA-256 of the UTF-8 bytes of [text], from Web Crypto (`crypto.subtle.digest`). */
    suspend fun sha256(text: String): ByteArray

    /**
     * FT-008: opens the browser's file chooser for one file of the [accept] MIME types.
     * Must be called straight from a click handler (browsers require a user gesture).
     * [onPicked] runs later with the file, or not at all if the user cancels.
     *
     * The file's bytes are read only when its reported size is at most [maxBytes] and its
     * type is one of [accept]; otherwise [PickedFile.bytes] is null and nothing of the
     * file is opened, so an oversized selection is refused without allocating it. The
     * default (non-browser platforms) never offers a file.
     */
    fun pickFile(accept: List<String>, maxBytes: Long, onPicked: (PickedFile) -> Unit) = Unit
}

/**
 * A file the user chose: its browser-reported MIME type and size, and its bytes when
 * they were read (within the size cap and an accepted type), else null.
 */
class PickedFile(val contentType: String, val size: Long, val bytes: ByteArray?)

class BrowserFeatureException(feature: String) : Exception("Browser feature unavailable: $feature")

/**
 * A URL split at `?` and `#` without decoding anything; [query] and
 * [fragmentParameters] are decoded by Ktor's `parseQueryString`.
 */
internal class UrlParts(url: String) {
    /** Everything before `?` or `#`: origin and path, used as the redirect URI. */
    val base: String
    val query: String
    val fragment: String

    init {
        val beforeHash = url.substringBefore('#')
        fragment = if ('#' in url) url.substringAfter('#') else ""
        base = beforeHash.substringBefore('?')
        query = if ('?' in beforeHash) beforeHash.substringAfter('?') else ""
    }

    val queryParameters: Parameters get() = parseQueryString(query)

    /** The fragment read as `key=value&…`, for an authorization response in fragment mode. */
    val fragmentParameters: Parameters get() = parseQueryString(fragment)

    /** True when the fragment is an app route (`#/…`) rather than a sign-in response. */
    val fragmentIsRoute: Boolean get() = fragment.isEmpty() || fragment.startsWith('/')
}
