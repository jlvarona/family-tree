package com.moxietech.family_tree.auth

import io.ktor.http.Url
import kotlinx.serialization.Serializable

/**
 * Runtime settings for the web client, served next to the bundle as
 * `config.json` (app/webApp/src/webMain/resources) so each deployment can point
 * at its own Keycloak realm and API without rebuilding. Nothing in it is
 * secret: `family-tree-web` is a public client and PKCE replaces a client secret.
 */
@Serializable
data class WebAppConfig(
    /** The realm's issuer URL as the browser reaches it; must equal the API's `OIDC_ISSUER`. */
    val issuer: String,
    /** The realm's public browser client (`family-tree-web` in ops/keycloak/realm-export.json). */
    val clientId: String,
    /**
     * Where `/api/v1/...` lives. Empty means the page's own origin: the dev
     * server proxies `/api` to the Ktor server, and production routes `/api`
     * to it on the same origin (README "Running the web client").
     */
    val apiBaseUrl: String = "",
) {
    private val realmBase: String get() = issuer.trimEnd('/')

    /** Keycloak's OIDC endpoints, by the same convention the server uses for the JWKS URI. */
    val authorizationEndpoint: String get() = "$realmBase/protocol/openid-connect/auth"
    val tokenEndpoint: String get() = "$realmBase/protocol/openid-connect/token"
    val endSessionEndpoint: String get() = "$realmBase/protocol/openid-connect/logout"

    /**
     * The client trusts the token endpoint's TLS instead of checking ID-token
     * signatures (OIDC Core 3.1.3.7 step 6), so the issuer and API must be HTTPS.
     * Plain HTTP is accepted only on loopback, for local development.
     */
    fun isUsable(): Boolean =
        clientId.isNotBlank() && isTrustedUrl(issuer) && (apiBaseUrl.isEmpty() || isTrustedUrl(apiBaseUrl))

    companion object {
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]")

        /** `https://…`, or `http://` to a loopback host. */
        fun isTrustedUrl(url: String): Boolean {
            // Ktor resolves scheme-less text against a default http://localhost, so require the scheme first.
            val https = url.startsWith("https://")
            if (!https && !url.startsWith("http://")) return false
            val host = try {
                Url(url).host
            } catch (e: Exception) {
                return false
            }
            return host.isNotEmpty() && (https || host in LOOPBACK_HOSTS)
        }
    }
}
