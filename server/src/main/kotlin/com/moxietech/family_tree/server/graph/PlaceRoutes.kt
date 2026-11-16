package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreatePlaceRequest
import com.moxietech.family_tree.api.UpdatePlaceRequest
import com.moxietech.family_tree.domain.PlaceId
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.placeRoutes() {
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/places") {
            post {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val request = call.receive<CreatePlaceRequest>()
                call.respond(HttpStatusCode.Created, PlaceService.createPlace(treeId, account.id, request))
            }
            get {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                call.respond(PlaceService.listPlaces(treeId, account.id))
            }
            put("/{placeId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val placeId = PlaceId(call.requirePathParam("placeId"))
                val request = call.receive<UpdatePlaceRequest>()
                call.respond(PlaceService.updatePlace(treeId, placeId, account.id, request))
            }
        }
    }
}
