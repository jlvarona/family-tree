package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.identity.currentAccount
import com.moxietech.family_tree.server.trees.TreeService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.personRoutes() {
    authenticate(OIDC_AUTH_NAME) {
        route("/api/v1/trees/{treeId}/people") {
            post {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val request = call.receive<CreatePersonRequest>()
                val (person, duplicates) = PersonService.createPerson(treeId, account.id, request)
                call.respond(HttpStatusCode.Created, CreatePersonResponse(person.toDto(), duplicates))
            }
            get {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                call.respond(PersonService.listPeople(treeId, account.id))
            }
            get("/{personId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                val personId = PersonId(call.requirePathParam("personId"))
                call.respond(PersonService.getPerson(treeId, personId, account.id))
            }
            get("/{personId}/ancestors") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                val personId = PersonId(call.requirePathParam("personId"))
                call.respond(PersonService.ancestorsOf(treeId, personId, account.id))
            }
            get("/{personId}/descendants") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                val personId = PersonId(call.requirePathParam("personId"))
                call.respond(PersonService.descendantsOf(treeId, personId, account.id))
            }
        }
    }
}

internal fun io.ktor.server.application.ApplicationCall.requireTreeId(): TreeId =
    TreeId(requirePathParam("treeId"))

internal fun io.ktor.server.application.ApplicationCall.requirePathParam(name: String): String =
    parameters[name] ?: throw NotFoundException("Missing path parameter '$name'")

/**
 * Write check for a POST under a tree, run before the body is decoded so a
 * caller who may not write gets 403 even for malformed JSON (FT-068). The
 * service repeats the check, which keeps direct service callers covered.
 */
internal suspend fun requireTreeWriter(treeId: TreeId, accountId: AccountId) {
    TreeService.getTreeForWriter(treeId, accountId)
}
