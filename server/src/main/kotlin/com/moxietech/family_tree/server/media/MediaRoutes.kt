package com.moxietech.family_tree.server.media

import com.moxietech.family_tree.api.CreateMediaUploadRequest
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.graph.requirePathParam
import com.moxietech.family_tree.server.graph.requireTreeId
import com.moxietech.family_tree.server.graph.requireTreeWriter
import com.moxietech.family_tree.server.identity.currentAccount
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * FT-008:
 * ```
 * POST /api/v1/trees/{treeId}/media                      CreateMediaUploadRequest → 201 MediaUploadDto (owner)
 * POST /api/v1/trees/{treeId}/media/{mediaId}/finalize   → 200 MediaDto (owner; retry-safe)
 * GET  /api/v1/trees/{treeId}/media/{mediaId}/download   → 200 MediaDownloadDto (members who may see it)
 * ```
 * Writes check the owner before the body or path ID is read (FT-068).
 */
fun Route.mediaRoutes(media: MediaService) {
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/media") {
            post {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val request = call.receive<CreateMediaUploadRequest>()
                call.respond(HttpStatusCode.Created, media.createUpload(treeId, account.id, request))
            }
            post("/{mediaId}/finalize") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                call.respond(media.finalize(treeId, call.requirePathParam("mediaId"), account.id))
            }
            get("/{mediaId}/download") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                call.respond(media.download(treeId, call.requirePathParam("mediaId"), account.id))
            }
        }
    }
}
