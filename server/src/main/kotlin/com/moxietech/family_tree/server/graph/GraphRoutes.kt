package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.identity.currentAccount
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.graphRoutes() {
    authenticate(OIDC_AUTH_NAME) {
        get("/api/v1/trees/{treeId}/graph") {
            val account = call.currentAccount()
            val treeId = call.requireTreeId()
            call.respond(GraphService.wholeTree(treeId, account.id))
        }
    }
}
