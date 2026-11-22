package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.api.CreateTreeRequest
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.errors.NotFoundException
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

/** [sharingEnabled]: the sharing gate (`INVITATIONS_ENABLED`), reported in the capability snapshot. */
fun Route.treeRoutes(sharingEnabled: Boolean = false) {
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees") {
            post {
                val account = call.currentAccount()
                val request = call.receive<CreateTreeRequest>()
                val tree = TreeService.createTree(account.id, request.name)
                call.respond(HttpStatusCode.Created, tree.toDto())
            }
            get {
                val account = call.currentAccount()
                val trees = TreeService.listTreesForAccount(account.id)
                call.respond(trees.map { it.toDto() })
            }
            get("/{treeId}") {
                val account = call.currentAccount()
                val treeId = TreeId(
                    call.parameters["treeId"] ?: throw NotFoundException("Missing treeId"),
                )
                val tree = TreeService.getTreeForReader(treeId, account.id)
                call.respond(tree.toDto())
            }
            // FT-014: what the caller may do here, for client controls (active members).
            get("/{treeId}/capabilities") {
                val account = call.currentAccount()
                val treeId = TreeId(call.parameters["treeId"] ?: throw NotFoundException("Missing treeId"))
                call.respond(TreeService.capabilities(treeId, account.id, sharingEnabled))
            }
        }
    }
}
