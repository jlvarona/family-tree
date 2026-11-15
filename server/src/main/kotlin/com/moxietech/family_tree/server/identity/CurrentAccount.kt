package com.moxietech.family_tree.server.identity

import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.server.config.displayName
import com.moxietech.family_tree.server.config.email
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal

/**
 * Resolves (and just-in-time provisions) the Account for the authenticated
 * caller of this request, by the token's verified issuer and subject. Every
 * route that needs the caller's account goes through here.
 */
suspend fun ApplicationCall.currentAccount(): Account {
    val principal = principal<JWTPrincipal>() ?: error("currentAccount() called on a route without OIDC authentication")
    return AccountService.getOrCreateForLogin(principal.oidcIdentity, principal.email, principal.displayName)
}
