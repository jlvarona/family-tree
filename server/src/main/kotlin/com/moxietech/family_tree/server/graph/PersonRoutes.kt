package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.api.MergePeopleRequest
import com.moxietech.family_tree.api.SetProfilePhotoRequest
import com.moxietech.family_tree.api.UpdatePersonRequest
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
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
            // FT-007: merge two records of one person. Owner-only (FT-012 keeps it so);
            // checked before the body is decoded, like every write (FT-068).
            post("/merge") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeOwner(treeId, account.id)
                val request = call.receive<MergePeopleRequest>()
                call.respond(PersonMergeService.merge(treeId, account.id, request))
            }
            get("/{personId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                val personId = PersonId(call.requirePathParam("personId"))
                call.respond(PersonService.getPerson(treeId, personId, account.id))
            }
            put("/{personId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                // Write check before the path ID or body is looked at: a non-owner
                // gets 403 whatever they send (FT-068, FT-001).
                requireTreeWriter(treeId, account.id)
                val personId = PersonId(call.requirePathParam("personId"))
                val request = call.receive<UpdatePersonRequest>()
                call.respond(PersonService.updatePerson(treeId, personId, account.id, request))
            }
            // FT-008: set or remove the profile photo. Write gate before the path ID or
            // body is looked at, like every write (FT-068).
            put("/{personId}/profile-photo") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                requireTreeWriter(treeId, account.id)
                val personId = PersonId(call.requirePathParam("personId"))
                val request = call.receive<SetProfilePhotoRequest>()
                call.respond(PersonService.setProfilePhoto(treeId, personId, account.id, request))
            }
            delete("/{personId}") {
                val account = call.currentAccount()
                val treeId = call.requireTreeId()
                // Write check before the path ID or ?version= is parsed (FT-068, FT-001).
                requireTreeWriter(treeId, account.id)
                val personId = PersonId(call.requirePathParam("personId"))
                PersonService.deletePerson(treeId, personId, account.id, call.versionParam())
                call.respond(HttpStatusCode.NoContent)
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

/** Every raw `version` query value of a DELETE; the service parses them after the write check. */
internal fun io.ktor.server.application.ApplicationCall.versionParam(): List<String>? =
    request.queryParameters.getAll("version")

internal fun io.ktor.server.application.ApplicationCall.requirePathParam(name: String): String =
    parameters[name] ?: throw NotFoundException("Missing path parameter '$name'")

/**
 * First write gate for a person, relationship, event or citation write under a tree,
 * run before the body is decoded so a caller who can't change anything gets 403 even
 * for malformed JSON (FT-068, FT-012 [TreeService.getTreeForEditor]). The service
 * repeats it, and decides the exact records inside its transaction (`WriteAccess`).
 */
internal suspend fun requireTreeWriter(treeId: TreeId, accountId: AccountId) {
    TreeService.getTreeForEditor(treeId, accountId)
}

/** FT-012: the same gate for tree-wide records (places, sources, media uploads): owner or whole-tree admin. */
internal suspend fun requireTreeWideWriter(treeId: TreeId, accountId: AccountId) {
    TreeService.getTreeForTreeWideWriter(treeId, accountId)
}

/** FT-012: the same gate for access management (invitations, grants): owner or whole-tree admin. */
internal suspend fun requireTreeManager(treeId: TreeId, accountId: AccountId) {
    TreeService.getTreeForManager(treeId, accountId)
}

/** The same gate for owner-only operations (merges, member linking). */
internal suspend fun requireTreeOwner(treeId: TreeId, accountId: AccountId) {
    TreeService.getTreeForWriter(treeId, accountId)
}
