package com.moxietech.family_tree.server.config

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import java.net.URI
import java.util.concurrent.TimeUnit

private const val DEFAULT_ISSUER = "http://localhost:8081/realms/family-tree"

/**
 * The API's resource-server identity in Keycloak. The browser client's access
 * tokens carry it in `aud` through an audience mapper
 * (ops/keycloak/realm-export.json).
 */
const val DEFAULT_API_AUDIENCE = "family-tree-server"

/**
 * Keycloak's `typ` claim for access tokens. ID tokens say `ID` and refresh
 * tokens `Refresh`; only access tokens may call the API.
 */
const val ACCESS_TOKEN_TYPE = "Bearer"

/**
 * Real OIDC: tokens are verified against Keycloak's live JWKS endpoint
 * (discovery doc path, not a hardcoded key) — dev and production exercise
 * the same authorization-code flow, only the realm URL differs
 * (docker-compose.yml runs Keycloak locally with a seeded "family-tree" realm).
 *
 * [audience] is the value an access token's `aud` must contain for this API
 * (`OIDC_AUDIENCE`, default [DEFAULT_API_AUDIENCE]).
 */
data class OidcSettings(
    val issuer: String = System.getenv("OIDC_ISSUER") ?: DEFAULT_ISSUER,
    val jwksUri: String = System.getenv("OIDC_JWKS_URI") ?: "$issuer/protocol/openid-connect/certs",
    val audience: String = System.getenv("OIDC_AUDIENCE") ?: DEFAULT_API_AUDIENCE,
    val leewaySeconds: Long = 5,
) {
    init {
        require(issuer.isNotBlank()) { "OIDC issuer must not be blank" }
        require(audience.isNotBlank()) { "OIDC audience must not be blank" }
    }
}

const val OIDC_AUTH_NAME = "oidc"

/**
 * Accepts a request only with a Keycloak access token that is signed by a key
 * in the JWKS, issued by [OidcSettings.issuer], intended for
 * [OidcSettings.audience], has an expiry that hasn't passed (beyond the leeway),
 * and carries a non-blank subject and an email. Anything else is a `401`.
 * The issuer + subject pair is what resolves the caller's account
 * (`identity.currentAccount`).
 */
fun AuthenticationConfig.configureOidc(settings: OidcSettings = OidcSettings()) {
    val jwkProvider: JwkProvider = JwkProviderBuilder(URI(settings.jwksUri).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    jwt(OIDC_AUTH_NAME) {
        verifier(jwkProvider, settings.issuer) {
            withAudience(settings.audience)
            withClaim("typ", ACCESS_TOKEN_TYPE)
            // java-jwt compares `exp` (with the leeway) only when it decodes to a timestamp;
            // presence alone lets `"exp": null` through, so validate below also requires a value.
            withClaimPresence("exp")
            acceptLeeway(settings.leewaySeconds)
        }
        validate { credential ->
            // The verified issuer + subject identify the local account (FT-003); email is profile data.
            val issuer = credential.payload.issuer
            val subject = credential.payload.subject
            val email = credential.payload.getClaim("email")?.asString()
            val expiresAt = credential.payload.expiresAtAsInstant
            val complete = !issuer.isNullOrBlank() && !subject.isNullOrBlank() && email != null && expiresAt != null
            if (complete) JWTPrincipal(credential.payload) else null
        }
    }
}

val JWTPrincipal.email: String get() = payload.getClaim("email").asString()

val JWTPrincipal.displayName: String
    get() = payload.getClaim("name")?.asString()
        ?: payload.getClaim("preferred_username")?.asString()
        ?: email
