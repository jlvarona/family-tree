package com.moxietech.family_tree.server.sources

import com.moxietech.family_tree.api.CreateCitationRequest
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.graph.requirePathParam
import com.moxietech.family_tree.server.graph.requireTreeId
import com.moxietech.family_tree.server.graph.requireTreeWriter
import com.moxietech.family_tree.server.identity.currentAccount
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * FT-002:
 * - `POST /api/v1/trees/{treeId}/citations` (live access to a person the target is about, FT-012) → 201 [com.moxietech.family_tree.api.CitationDto]
 * - `GET /api/v1/trees/{treeId}/{people|events|unions}/{id}/citations` (active members)
 *
 * There is no tree-wide citation listing: every read goes through one target and
 * that target's visibility rule (see [CitationService]).
 */
fun Route.citationRoutes() {
    authenticate(OIDC_AUTH_NAME) {
        post("/api/v1/trees/{treeId}/citations") {
            val account = call.currentAccount()
            val treeId = call.requireTreeId()
            requireTreeWriter(treeId, account.id)
            val request = call.receive<CreateCitationRequest>()
            call.respond(HttpStatusCode.Created, CitationService.createCitation(treeId, account.id, request))
        }
        for (kind in CitationTargetKind.entries) {
            get("/api/v1/trees/{treeId}/${kind.collection}/{${kind.pathField}}/citations") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                val targetId = call.requirePathParam(kind.pathField)
                call.respond(CitationService.listCitations(treeId, kind, targetId, account.id))
            }
        }
    }
}
