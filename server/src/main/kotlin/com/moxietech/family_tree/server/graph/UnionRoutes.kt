package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.UpdateUnionChildRequest
import com.moxietech.family_tree.api.UpdateUnionRequest
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.UnionId
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
fun Route.unionRoutes(proposals: ChangeProposalSettings = ChangeProposalSettings.DISABLED) {
    val mode = proposals.writeMode
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/unions") {
            post {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val request = call.receive<CreateUnionRequest>()
                call.respondOutcome(UnionService.createUnionOutcome(treeId, account.id, request, mode), HttpStatusCode.Created)
            }
            get {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                call.respond(UnionService.listUnions(treeId, account.id))
            }
            put("/{unionId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val unionId = UnionId(call.requirePathParam("unionId"))
                val request = call.receive<UpdateUnionRequest>()
                call.respondOutcome(UnionService.updateUnionOutcome(treeId, unionId, account.id, request, mode))
            }
            delete("/{unionId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val unionId = UnionId(call.requirePathParam("unionId"))
                call.respondDeleteOutcome(UnionService.deleteUnionOutcome(treeId, unionId, account.id, call.versionParam(), mode))
            }
            post("/{unionId}/children") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                // UnionService.addChild validates the ID after the write check, so a
                // non-owner gets 403 whatever the union ID (FT-068, FT-070).
                val unionId = UnionId(call.requirePathParam("unionId"))
                val request = call.receive<AddUnionChildRequest>()
                call.respondOutcome(UnionService.addChildOutcome(treeId, unionId, account.id, request, mode), HttpStatusCode.Created)
            }
            put("/{unionId}/children/{childId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val unionId = UnionId(call.requirePathParam("unionId"))
                val childId = PersonId(call.requirePathParam("childId"))
                val request = call.receive<UpdateUnionChildRequest>()
                call.respondOutcome(UnionService.updateChildOutcome(treeId, unionId, childId, account.id, request, mode))
            }
            delete("/{unionId}/children/{childId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val unionId = UnionId(call.requirePathParam("unionId"))
                val childId = PersonId(call.requirePathParam("childId"))
                call.respondDeleteOutcome(UnionService.deleteChildOutcome(treeId, unionId, childId, account.id, call.versionParam(), mode))
            }
        }
    }
}
