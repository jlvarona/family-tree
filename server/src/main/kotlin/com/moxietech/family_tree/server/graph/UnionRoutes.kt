package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.identity.currentAccount
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.unionRoutes() {
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/unions") {
            post {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                val request = call.receive<CreateUnionRequest>()
                call.respond(HttpStatusCode.Created, UnionService.createUnion(treeId, account.id, request))
            }
            get {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                call.respond(UnionService.listUnions(treeId, account.id))
            }
            post("/{unionId}/children") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                val unionId = UnionId(call.requirePathParam("unionId"))
                val request = call.receive<AddUnionChildRequest>()
                call.respond(HttpStatusCode.Created, UnionService.addChild(treeId, unionId, account.id, request))
            }
        }
    }
}
