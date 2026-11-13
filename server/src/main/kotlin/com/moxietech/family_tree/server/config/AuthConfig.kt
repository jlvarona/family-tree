package com.moxietech.family_tree.server.config

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Real OIDC: tokens are verified against Keycloak's live JWKS endpoint
 * (discovery doc path, not a hardcoded key) — dev and production exercise
 * the same authorization-code flow, only the realm URL differs
 * (ARCHITECTURE.md open decisions; docker-compose.yml runs Keycloak
 * locally with a seeded "family-tree" realm).
 */
data class OidcSettings(
    val issuer: String = System.getenv("OIDC_ISSUER")
        ?: "http://localhost:8081/realms/family-tree",
    val jwksUri: String = System.getenv("OIDC_JWKS_URI")
        ?: "${System.getenv("OIDC_ISSUER") ?: "http://localhost:8081/realms/family-tree"}/protocol/openid-connect/certs",
)

const val OIDC_AUTH_NAME = "oidc"

fun AuthenticationConfig.configureOidc(settings: OidcSettings = OidcSettings()) {
    val jwkProvider = JwkProviderBuilder(URI(settings.jwksUri).toURL())
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    jwt(OIDC_AUTH_NAME) {
        verifier(jwkProvider, settings.issuer) {
            acceptLeeway(5)
        }
        validate { credential ->
            val subject = credential.payload.subject
            val email = credential.payload.getClaim("email")?.asString()
            if (subject != null && email != null) JWTPrincipal(credential.payload) else null
        }
    }
}

val JWTPrincipal.email: String get() = payload.getClaim("email").asString()

val JWTPrincipal.displayName: String
    get() = payload.getClaim("name")?.asString()
        ?: payload.getClaim("preferred_username")?.asString()
        ?: email
