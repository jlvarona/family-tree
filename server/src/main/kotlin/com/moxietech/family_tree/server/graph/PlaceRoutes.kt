package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreatePlaceRequest
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

fun Route.placeRoutes() {
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/places") {
            post {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                val request = call.receive<CreatePlaceRequest>()
                call.respond(HttpStatusCode.Created, PlaceService.createPlace(treeId, account.id, request))
            }
            get {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                call.respond(PlaceService.listPlaces(treeId, account.id))
            }
        }
    }
}
