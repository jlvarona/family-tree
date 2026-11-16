package com.moxietech.family_tree.auth

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
}
