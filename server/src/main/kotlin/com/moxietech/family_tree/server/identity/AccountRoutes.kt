package com.moxietech.family_tree.server.identity

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.accountRoutes() {
    authenticate(OIDC_AUTH_NAME) {
        get("/api/v1/me") {
            call.respond(call.currentAccount().toDto())
        }
    }
}

fun Account.toDto() = AccountDto(
    id = id.value,
    email = email,
    displayName = displayName,
    locale = locale.name.lowercase(),
)
