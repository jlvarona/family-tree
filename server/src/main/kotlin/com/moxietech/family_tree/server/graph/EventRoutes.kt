package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.domain.PersonId
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

fun Route.eventRoutes() {
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/events") {
            post {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                val request = call.receive<CreateEventRequest>()
                call.respond(HttpStatusCode.Created, EventService.createEvent(treeId, account.id, request))
            }
        }
        route("/api/v1/trees/{treeId}/people/{personId}/events") {
            get {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                val personId = PersonId(call.requirePathParam("personId"))
                call.respond(EventService.listEventsForPerson(treeId, personId, account.id))
            }
        }
    }
}
