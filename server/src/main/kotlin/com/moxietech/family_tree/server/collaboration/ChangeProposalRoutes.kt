package com.moxietech.family_tree.server.collaboration

import com.moxietech.family_tree.api.ApproveProposalRequest
import com.moxietech.family_tree.api.DecideProposalRequest
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.graph.requirePathParam
import com.moxietech.family_tree.server.graph.requireTreeId
import com.moxietech.family_tree.server.identity.currentAccount
import com.moxietech.family_tree.server.permissions.WriteMode
import com.moxietech.family_tree.server.permissions.WriteOutcome
import com.moxietech.family_tree.server.trees.TreeService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * FT-014 review routes, installed only when [ChangeProposalSettings.enabled] (otherwise the
 * plain 404 of an unknown route, like the invitation gate):
 * - `GET  …/proposals`: the proposals the caller made or may review, newest first.
 * - `GET  …/proposals/{proposalId}`: one of them (404 otherwise).
 * - `POST …/proposals/{proposalId}/approve` [ApproveProposalRequest]: tree owner or the
 *   branch owner of every affected person; applies the approved fields.
 * - `POST …/proposals/{proposalId}/reject` [DecideProposalRequest]: same reviewers.
 * - `POST …/proposals/{proposalId}/withdraw` [DecideProposalRequest]: the proposer.
 * Each answers the proposal as the caller may see it.
 */
fun Route.changeProposalRoutes(settings: ChangeProposalSettings) {
    if (!settings.enabled) return
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/proposals") {
            get {
                val account = call.currentAccount()
                call.respond(ChangeProposalService.list(call.requireTreeId(), account.id))
            }
            get("/{proposalId}") {
                val account = call.currentAccount()
                call.respond(ChangeProposalService.get(call.requireTreeId(), call.requirePathParam("proposalId"), account.id))
            }
            post("/{proposalId}/approve") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                ChangeProposalService.requireReviewer(treeId, account.id)
                val request = call.receive<ApproveProposalRequest>()
                call.respond(ChangeProposalService.approve(treeId, call.requirePathParam("proposalId"), account.id, request))
            }
            post("/{proposalId}/reject") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                ChangeProposalService.requireReviewer(treeId, account.id)
                val request = call.receive<DecideProposalRequest>()
                call.respond(ChangeProposalService.reject(treeId, call.requirePathParam("proposalId"), account.id, request))
            }
            post("/{proposalId}/withdraw") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                TreeService.getTreeForReader(treeId, account.id)
                val request = call.receive<DecideProposalRequest>()
                call.respond(ChangeProposalService.withdraw(treeId, call.requirePathParam("proposalId"), account.id, request))
            }
        }
    }
}

/** FT-014: the write mode graph routes use: propose when proposals are enabled. */
internal val ChangeProposalSettings.writeMode: WriteMode get() = if (enabled) WriteMode.PROPOSE else WriteMode.DIRECT

/** A write's response: [status] with the applied value, or `202 Accepted` with the queued proposal. */
internal suspend inline fun <reified T : Any> ApplicationCall.respondOutcome(outcome: WriteOutcome<T>, status: HttpStatusCode = HttpStatusCode.OK) {
    when (outcome) {
        is WriteOutcome.Applied -> respond(status, outcome.value)
        is WriteOutcome.Proposed -> respond(HttpStatusCode.Accepted, outcome.proposal)
    }
}

/** A delete's response: `204 No Content`, or `202 Accepted` with the queued proposal. */
internal suspend fun ApplicationCall.respondDeleteOutcome(outcome: WriteOutcome<Unit>) {
    when (outcome) {
        is WriteOutcome.Applied -> respond(HttpStatusCode.NoContent)
        is WriteOutcome.Proposed -> respond(HttpStatusCode.Accepted, outcome.proposal)
    }
}
