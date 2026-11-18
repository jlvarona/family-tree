package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.api.CreateGrantRequest
import com.moxietech.family_tree.api.UpdateGrantRequest
import com.moxietech.family_tree.domain.GrantId
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.errors.parseVersionParam
import com.moxietech.family_tree.server.graph.requirePathParam
import com.moxietech.family_tree.server.graph.requireTreeId
import com.moxietech.family_tree.server.graph.requireTreeManager
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
 * FT-012 grant management, installed only by `invitationRoutes` (the same default-off
 * `INVITATIONS_ENABLED` gate: while off these routes don't exist and every caller gets the
 * plain `404` of an unknown route). Each route checks the owner or whole-tree admin
 * ([com.moxietech.family_tree.server.trees.TreeService.getTreeForManager]) before reading
 * the path's grant ID or the body; [GrantService] repeats it under locks in the write.
 *
 * - `GET    /api/v1/trees/{treeId}/grants` → every grant of the tree.
 * - `POST   /api/v1/trees/{treeId}/grants` [CreateGrantRequest] → 201 the grant.
 * - `PUT    /api/v1/trees/{treeId}/grants/{grantId}` [UpdateGrantRequest] → the grant at its new version.
 * - `DELETE /api/v1/trees/{treeId}/grants/{grantId}?version=N` → 204.
 */
fun Route.grantRoutes() {
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/grants") {
            get {
                val account = call.currentAccount()
                call.respond(GrantService.listGrants(call.requireTreeId(), account.id))
            }
            post {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeManager(treeId, account.id)
                val request = call.receive<CreateGrantRequest>()
                call.respond(HttpStatusCode.Created, GrantService.createGrant(treeId, account.id, request))
            }
            put("/{grantId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeManager(treeId, account.id)
                val grantId = GrantId(call.requirePathParam("grantId"))
                val request = call.receive<UpdateGrantRequest>()
                call.respond(GrantService.updateGrant(treeId, grantId, account.id, request))
            }
            delete("/{grantId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeManager(treeId, account.id)
                val grantId = GrantId(call.requirePathParam("grantId"))
                GrantService.deleteGrant(treeId, grantId, account.id, parseVersionParam(call.versionParam()))
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
