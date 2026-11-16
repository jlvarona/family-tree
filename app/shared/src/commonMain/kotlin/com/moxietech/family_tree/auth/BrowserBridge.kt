package com.moxietech.family_tree.auth

/**
 * The few browser facilities the web workflow needs, behind an interface so the
 * sign-in, routing and state code stays in common code and is unit tested with
 * a fake. The js and wasmJs targets implement it over `window` (webMain
 * `createBrowserBridge`).
 *
 * Only short-lived values go into [sessionGet]/[sessionSet] (per tab, cleared
 * when the tab closes): the one-time sign-in request (state, PKCE verifier,
 * nonce, return route) and a flag that this tab was signed in. Tokens are kept
 * in memory only.
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

    fun sessionRemove(key: String)

    /** Cryptographically secure random bytes (`crypto.getRandomValues`). */
    fun randomBytes(count: Int): ByteArray
}

/** Splits a URL into the part before `?`/`#`, its query parameters and its fragment. */
internal data class ParsedUrl(val base: String, val query: Map<String, String>, val fragment: String) {
    companion object {
        fun parse(url: String): ParsedUrl {
            val hashIndex = url.indexOf('#')
            val beforeHash = if (hashIndex >= 0) url.substring(0, hashIndex) else url
            val fragment = if (hashIndex >= 0) url.substring(hashIndex + 1) else ""
            val queryIndex = beforeHash.indexOf('?')
            val base = if (queryIndex >= 0) beforeHash.substring(0, queryIndex) else beforeHash
            val query = if (queryIndex >= 0) parseQuery(beforeHash.substring(queryIndex + 1)) else emptyMap()
            return ParsedUrl(base, query, fragment)
        }

        fun parseQuery(text: String): Map<String, String> = text.split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) decode(pair) to "" else decode(pair.substring(0, eq)) to decode(pair.substring(eq + 1))
            }

        private fun decode(text: String): String = UrlCodec.decode(text.replace('+', ' '))
    }
}

/** Percent-encoding for query strings (RFC 3986 unreserved characters pass through). */
internal object UrlCodec {

    private const val HEX = "0123456789ABCDEF"

    fun encode(text: String): String {
        val out = StringBuilder()
        for (byte in text.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
        }
        return out.toString()
    }

    fun decode(text: String): String {
        if ('%' !in text) return text
        val bytes = ArrayList<Byte>(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%' && i + 2 < text.length) {
                val value = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (value != null) {
                    bytes.add(value.toByte())
                    i += 3
                    continue
                }
            }
            bytes.addAll(ch.toString().encodeToByteArray().toList())
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    fun query(params: List<Pair<String, String>>): String =
        params.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
}
