package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.api.CreateInvitationRequest
import com.moxietech.family_tree.api.LinkMemberPersonRequest
import com.moxietech.family_tree.api.RedeemInvitationRequest
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.graph.requirePathParam
import com.moxietech.family_tree.server.graph.requireTreeId
import com.moxietech.family_tree.server.graph.requireTreeWriter
import com.moxietech.family_tree.server.graph.versionParam
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

/**
 * FT-011 routes, installed only when invitations are enabled
 * ([InvitationSettings.enabled]); while off they don't exist, so every caller, signed in
 * or not, gets the server's plain `404` for an unknown route. Owner routes run the
 * Phase 1 write check before decoding a body (FT-068), as every other tree write does.
 *
 * - `POST   /api/v1/trees/{treeId}/invitations` (owner) — create; the token is in the response once.
 * - `GET    /api/v1/trees/{treeId}/invitations` (owner) — pending and expired invitations, never tokens.
 * - `DELETE /api/v1/trees/{treeId}/invitations/{invitationId}?version=N` (owner) — revoke.
 * - `POST   /api/v1/trees/{treeId}/invitations/redeem` (any signed-in account) — accept; the
 *   tree is the link's, the token is in the body only. Not a member check: the caller
 *   isn't a member yet.
 * - `GET    /api/v1/trees/{treeId}/members` (owner) — members and their linked people.
 * - `PUT    /api/v1/trees/{treeId}/members/{accountId}/person` (owner) — link or unlink a person.
 */
fun Route.invitationRoutes(invitations: InvitationService, members: MemberService) {
    if (!invitations.enabled) return
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/invitations") {
            post {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val request = call.receive<CreateInvitationRequest>()
                call.respond(HttpStatusCode.Created, invitations.create(treeId, account, request))
            }
            get {
                val account = call.currentAccount()
                call.respond(invitations.list(call.requireTreeId(), account.id))
            }
            delete("/{invitationId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                invitations.revoke(treeId, call.requirePathParam("invitationId"), account.id, call.versionParam())
                call.respond(HttpStatusCode.NoContent)
            }
            post("/redeem") {
                val account = call.currentAccount()
                val treeId = call.requirePathParam("treeId")
                val request = call.receive<RedeemInvitationRequest>()
                call.respond(invitations.redeem(treeId, account, request))
            }
        }
        route("/api/v1/trees/{treeId}/members") {
            get {
                val account = call.currentAccount()
                call.respond(members.list(call.requireTreeId(), account.id))
            }
            put("/{accountId}/person") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val request = call.receive<LinkMemberPersonRequest>()
                call.respond(members.linkPerson(treeId, call.requirePathParam("accountId"), account.id, request))
            }
        }
    }
}
