package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.UpdateEventRequest
import com.moxietech.family_tree.domain.EventId
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.server.collaboration.ChangeProposalSettings
import com.moxietech.family_tree.server.collaboration.respondDeleteOutcome
import com.moxietech.family_tree.server.collaboration.respondOutcome
import com.moxietech.family_tree.server.collaboration.writeMode
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.identity.currentAccount
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** FT-014: with [proposals] enabled, a write the caller can't make live answers `202` with the queued proposal. */
fun Route.eventRoutes(proposals: ChangeProposalSettings = ChangeProposalSettings.DISABLED) {
    val mode = proposals.writeMode
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/events") {
            post {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val request = call.receive<CreateEventRequest>()
                call.respondOutcome(EventService.createEventOutcome(treeId, account.id, request, mode), HttpStatusCode.Created)
            }
            put("/{eventId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val eventId = EventId(call.requirePathParam("eventId"))
                val request = call.receive<UpdateEventRequest>()
                call.respondOutcome(EventService.updateEventOutcome(treeId, eventId, account.id, request, mode))
            }
            delete("/{eventId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val eventId = EventId(call.requirePathParam("eventId"))
                call.respondDeleteOutcome(EventService.deleteEventOutcome(treeId, eventId, account.id, call.versionParam(), mode))
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
