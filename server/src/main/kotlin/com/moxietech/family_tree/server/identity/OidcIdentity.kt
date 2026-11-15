package com.moxietech.family_tree.server.identity

import io.ktor.server.auth.jwt.JWTPrincipal

/**
 * Who the caller is: the verified token's issuer and subject. This pair, not
 * the email claim, is the only key that resolves a local account (FT-003).
 */
data class OidcIdentity(val issuer: String, val subject: String) {
    init {
        require(issuer.isNotBlank()) { "OIDC issuer must not be blank" }
        require(subject.isNotBlank()) { "OIDC subject must not be blank" }
    }
}

/** The identity of a principal accepted by `configureOidc`, which requires both claims. */
val JWTPrincipal.oidcIdentity: OidcIdentity
    get() = OidcIdentity(
        issuer = payload.issuer ?: error("Authenticated token has no issuer"),
        subject = payload.subject ?: error("Authenticated token has no subject"),
    )
