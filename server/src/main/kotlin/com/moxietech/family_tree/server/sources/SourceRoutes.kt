package com.moxietech.family_tree.server.sources

import com.moxietech.family_tree.api.CreateSourceRequest
import com.moxietech.family_tree.api.UpdateSourceRequest
import com.moxietech.family_tree.domain.SourceId
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.graph.requirePathParam
import com.moxietech.family_tree.server.graph.requireTreeId
import com.moxietech.family_tree.server.graph.requireTreeWideWriter
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

fun Route.sourceRoutes() {
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/sources") {
            post {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWideWriter(treeId, account.id)
                val request = call.receive<CreateSourceRequest>()
                call.respond(HttpStatusCode.Created, SourceService.createSource(treeId, account.id, request))
            }
            get {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                call.respond(SourceService.listSources(treeId, account.id))
            }
            put("/{sourceId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWideWriter(treeId, account.id)
                val sourceId = SourceId(call.requirePathParam("sourceId"))
                val request = call.receive<UpdateSourceRequest>()
                call.respond(SourceService.updateSource(treeId, sourceId, account.id, request))
            }
            delete("/{sourceId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWideWriter(treeId, account.id)
                val sourceId = SourceId(call.requirePathParam("sourceId"))
                SourceService.deleteSource(treeId, sourceId, account.id, call.versionParam())
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
