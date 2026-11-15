package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.DuplicateCandidateDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.requireValid
import com.moxietech.family_tree.server.trees.TreeService
import com.moxietech.family_tree.validation.Validation
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

object PersonService {

    suspend fun createPerson(
        treeId: TreeId,
        callerAccountId: AccountId,
        request: CreatePersonRequest,
    ): Pair<Person, List<DuplicateCandidateDto>> {
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        Validation.validatePersonName(request.givenNames).requireValid()
        val sex = runCatching { Sex.valueOf(request.sex) }.getOrElse {
            throw IllegalArgumentException("Unknown sex '${request.sex}'")
        }

        return withContext(DatabaseDispatcher) {
            transaction {
                val existing = PersonRepository.listByTree(Uuid.parse(treeId.value))
                val duplicates = ViewerPolicy(tree, callerAccountId).duplicateCandidates(
                    givenNames = request.givenNames,
                    surnamePaternal = request.surnamePaternal,
                    surnameMaternal = request.surnameMaternal,
                    existing = existing,
                )

                val person = PersonRepository.insert(
                    treeId = Uuid.parse(treeId.value),
                    givenNames = request.givenNames,
                    surnamePaternal = request.surnamePaternal,
                    surnameMaternal = request.surnameMaternal,
                    sex = sex,
                    isLiving = request.isLiving,
                    bio = request.bio,
                    createdByAccountId = Uuid.parse(callerAccountId.value),
                )

                AuditLogRepository.append(
                    treeId = Uuid.parse(treeId.value),
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "Person",
                    entityId = person.id.value,
                    action = AuditAction.CREATE,
                    beforeJson = null,
                    afterJson = AuditJson.encodeToString(person.toDto()),
                )

                person to duplicates
            }
        }
    }

    suspend fun listPeople(treeId: TreeId, callerAccountId: AccountId): List<PersonDto> {
        val tree = TreeService.getTreeForReader(treeId, callerAccountId)
        return withContext(DatabaseDispatcher) {
            PersonRepository.listByTree(Uuid.parse(treeId.value)).map(ViewerPolicy(tree, callerAccountId)::project)
        }
    }

    suspend fun getPerson(treeId: TreeId, personId: PersonId, callerAccountId: AccountId): PersonDto {
        val tree = TreeService.getTreeForReader(treeId, callerAccountId)
        val person = withContext(DatabaseDispatcher) {
            TreeScopedLookups.requirePerson(Uuid.parse(treeId.value), Uuid.parse(personId.value))
        }
        return ViewerPolicy(tree, callerAccountId).project(person)
    }

    /** 404 when the anchor isn't in [treeId], rather than an empty list that hides the mistake. */
    suspend fun ancestorsOf(treeId: TreeId, personId: PersonId, callerAccountId: AccountId): List<PersonDto> {
        val tree = TreeService.getTreeForReader(treeId, callerAccountId)
        val treeUuid = Uuid.parse(treeId.value)
        val personUuid = Uuid.parse(personId.value)
        return withContext(DatabaseDispatcher) {
            TreeScopedLookups.requirePerson(treeUuid, personUuid)
            PersonRepository.ancestorsOf(personUuid, treeUuid)
        }.map(ViewerPolicy(tree, callerAccountId)::project)
    }

    /** 404 when the anchor isn't in [treeId], rather than an empty list that hides the mistake. */
    suspend fun descendantsOf(treeId: TreeId, personId: PersonId, callerAccountId: AccountId): List<PersonDto> {
        val tree = TreeService.getTreeForReader(treeId, callerAccountId)
        val treeUuid = Uuid.parse(treeId.value)
        val personUuid = Uuid.parse(personId.value)
        return withContext(DatabaseDispatcher) {
            TreeScopedLookups.requirePerson(treeUuid, personUuid)
            PersonRepository.descendantsOf(personUuid, treeUuid)
        }.map(ViewerPolicy(tree, callerAccountId)::project)
    }
}
