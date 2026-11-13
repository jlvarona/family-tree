package com.moxietech.family_tree.server.identity

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.config.displayName
import com.moxietech.family_tree.server.config.email
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.accountRoutes() {
    authenticate(OIDC_AUTH_NAME) {
        get("/api/v1/me") {
            val principal = call.principal<JWTPrincipal>() ?: error("Route is authenticated; principal must be present")
            val account = AccountService.getOrCreateForLogin(principal.email, principal.displayName)
            call.respond(account.toDto())
        }
    }
}

fun Account.toDto() = AccountDto(
    id = id.value,
    email = email,
    displayName = displayName,
    locale = locale.name.lowercase(),
)
