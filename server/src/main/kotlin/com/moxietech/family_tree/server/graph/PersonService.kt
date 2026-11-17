package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.DuplicateCandidateDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.SetProfilePhotoRequest
import com.moxietech.family_tree.api.UpdatePersonRequest
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.media.MediaPurpose
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.parseOptionalUuidField
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.media.MediaAttachments
import com.moxietech.family_tree.server.media.mediaInUse
import com.moxietech.family_tree.server.permissions.GraphGeneration
import com.moxietech.family_tree.server.errors.parseVersionParam
import com.moxietech.family_tree.server.errors.referenced
import com.moxietech.family_tree.server.errors.requireValid
import com.moxietech.family_tree.server.errors.staleVersion
import com.moxietech.family_tree.server.trees.TreeService
import com.moxietech.family_tree.validation.Validation
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.Connection
import java.sql.SQLException
import kotlin.uuid.Uuid

/** V6: the deferred constraint keeping each media row the photo of at most one person. */
internal const val PROFILE_MEDIA_UNIQUE = "person_profile_media_id_key"

object PersonService {

    suspend fun createPerson(
        treeId: TreeId,
        callerAccountId: AccountId,
        request: CreatePersonRequest,
    ): Pair<Person, List<DuplicateCandidateDto>> {
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        Validation.validatePersonName(request.givenNames).requireValid()
        val sex = runCatching { Sex.valueOf(request.sex) }.getOrElse {
            throw ValidationException(listOf("Unknown sex '${request.sex}'"))
        }

        return withContext(DatabaseDispatcher) {
            transaction {
                // Excludes a grant-scope rebuild while the person is added (FT-010).
                GraphWriteLock.shared(Uuid.parse(treeId.value))
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
                // A new person joins every whole-tree grant scope (FT-010).
                GraphGeneration.advance(Uuid.parse(treeId.value))

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

    /**
     * FT-001: replaces a person's editable fields, owner only. The person is
     * resolved inside [treeId] (another tree's ID is the same 404 as a missing
     * one), and [UpdatePersonRequest.version] must still be the stored version:
     * otherwise the update is a 409 that changes neither the person nor the
     * audit log. The write and its before/after audit share one transaction.
     */
    suspend fun updatePerson(
        treeId: TreeId,
        personId: PersonId,
        callerAccountId: AccountId,
        request: UpdatePersonRequest,
    ): PersonDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        val personUuid = parseUuidField("personId", personId.value)
        Validation.validatePersonName(request.givenNames).requireValid()
        val sex = runCatching { Sex.valueOf(request.sex) }.getOrElse {
            throw ValidationException(listOf("Unknown sex '${request.sex}'"))
        }

        return withContext(DatabaseDispatcher) {
            transaction {
                val treeUuid = Uuid.parse(treeId.value)
                val before = TreeScopedLookups.requirePerson(treeUuid, personUuid)
                if (before.version != request.version) throw staleVersion("Person", before.id.value)
                val after = PersonRepository.update(
                    treeId = treeUuid,
                    id = personUuid,
                    expectedVersion = request.version,
                    givenNames = request.givenNames,
                    surnamePaternal = request.surnamePaternal,
                    surnameMaternal = request.surnameMaternal,
                    sex = sex,
                    isLiving = request.isLiving,
                    bio = request.bio,
                ) ?: throw staleVersion("Person", before.id.value)
                val dto = after.toDto()
                AuditLogRepository.append(
                    treeId = treeUuid,
                    actorAccountId = Uuid.parse(callerAccountId.value),
                    entityType = "Person",
                    entityId = after.id.value,
                    action = AuditAction.UPDATE,
                    beforeJson = AuditJson.encodeToString(before.toDto()),
                    afterJson = AuditJson.encodeToString(dto),
                )
                dto
            }
        }
    }

    /**
     * FT-008: sets or removes a person's profile photo, owner only
     * (`PUT …/people/{personId}/profile-photo`). One READ COMMITTED transaction that
     * first takes [GraphWriteLock] shared (so it can't interleave with a merge); then
     * the media is locked and checked in the URL's tree (404 missing or foreign,
     * 409 not verified, 400 another purpose, 409 already another person's photo),
     * then the person is resolved in the same tree (404) and updated at the version
     * the client read (409 stale), and the before/after audit is written. A replaced
     * photo stays stored as unattached media; only the owner can still read it.
     */
    suspend fun setProfilePhoto(
        treeId: TreeId,
        personId: PersonId,
        callerAccountId: AccountId,
        request: SetProfilePhotoRequest,
    ): PersonDto {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        val personUuid = parseUuidField("personId", personId.value)
        val mediaUuid = parseOptionalUuidField("mediaId", request.mediaId)
        val treeUuid = Uuid.parse(treeId.value)
        return try {
            withContext(DatabaseDispatcher) {
                transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                    // First statement, before any row lock (FT-007 order): a merge holds the
                    // tree exclusive and locks people before the survivor's photo foreign key
                    // touches the media row, so taking media then person here could deadlock
                    // with it. Waiting here, the assignment runs after the merge and then sees
                    // the merged result (survivor: stale version 409; retired person: 404).
                    GraphWriteLock.shared(treeUuid)
                    if (mediaUuid != null) {
                        MediaAttachments.lockForAttaching(treeUuid, mediaUuid, MediaPurpose.PROFILE_PHOTO)
                        val holder = PersonRepository.findByProfileMedia(treeUuid, mediaUuid)
                        if (holder != null && Uuid.parse(holder.id.value) != personUuid) throw mediaInUse(mediaUuid)
                    }
                    val before = TreeScopedLookups.requirePerson(treeUuid, personUuid)
                    if (before.version != request.version) throw staleVersion("Person", before.id.value)
                    val after = PersonRepository.setProfileMedia(treeUuid, personUuid, request.version, mediaUuid)
                        ?: throw staleVersion("Person", before.id.value)
                    val dto = after.toDto()
                    AuditLogRepository.append(
                        treeId = treeUuid,
                        actorAccountId = Uuid.parse(callerAccountId.value),
                        entityType = "Person",
                        entityId = after.id.value,
                        action = AuditAction.UPDATE,
                        beforeJson = AuditJson.encodeToString(before.toDto()),
                        afterJson = AuditJson.encodeToString(dto),
                    )
                    dto
                }
            }
        } catch (e: SQLException) {
            // The deferred one-photo-per-media constraint is checked at commit. The media
            // lock makes it unreachable from this route; kept so it is a 409, never a 500.
            if (mediaUuid != null && e.isUniqueViolation(PROFILE_MEDIA_UNIQUE)) throw mediaInUse(mediaUuid)
            throw e
        }
    }

    /**
     * FT-001: deletes an unreferenced person, owner only, at the current
     * [version] (see [RecordDeletion] for the steps and references). Referenced
     * or stale is a 409 that writes nothing; success audits before = the person,
     * after = null, in the same transaction.
     */
    suspend fun deletePerson(treeId: TreeId, personId: PersonId, callerAccountId: AccountId, version: List<String>?) {
        TreeService.getTreeForWriter(treeId, callerAccountId)
        val personUuid = parseUuidField("personId", personId.value)
        val expected = parseVersionParam(version)
        val treeUuid = Uuid.parse(treeId.value)
        RecordDeletion.guarded("Person", personUuid.toString(), RecordDeletion.PERSON_FOREIGN_KEYS) {
            // First, before the person's row lock: a grant-scope rebuild (which needs
            // FOR KEY SHARE on the people it stores) never runs while this holds the
            // person FOR UPDATE (FT-010).
            GraphWriteLock.shared(treeUuid)
            val before = TreeScopedLookups.requirePerson(treeUuid, personUuid)
            if (before.version != expected) throw staleVersion("Person", before.id.value)
            if (RecordDeletion.personIsReferenced(treeUuid, personUuid)) throw referenced("Person", before.id.value)
            // The person's cached grant-scope rows are derived and cascade with it (V7).
            if (PersonRepository.delete(treeUuid, personUuid, expected) == 0) throw staleVersion("Person", before.id.value)
            if (RecordDeletion.isCited(treeUuid, RecordDeletion.PERSON, personUuid)) throw referenced("Person", before.id.value)
            GraphGeneration.advance(treeUuid)
            AuditLogRepository.append(
                treeId = treeUuid,
                actorAccountId = Uuid.parse(callerAccountId.value),
                entityType = "Person",
                entityId = before.id.value,
                action = AuditAction.DELETE,
                beforeJson = AuditJson.encodeToString(before.toDto()),
                afterJson = null,
            )
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
            TreeScopedLookups.requirePerson(Uuid.parse(treeId.value), parseUuidField("personId", personId.value))
        }
        return ViewerPolicy(tree, callerAccountId).project(person)
    }

    /** 404 when the anchor isn't in [treeId], rather than an empty list that hides the mistake. */
    suspend fun ancestorsOf(treeId: TreeId, personId: PersonId, callerAccountId: AccountId): List<PersonDto> {
        val tree = TreeService.getTreeForReader(treeId, callerAccountId)
        val treeUuid = Uuid.parse(treeId.value)
        val personUuid = parseUuidField("personId", personId.value)
        return withContext(DatabaseDispatcher) {
            TreeScopedLookups.requirePerson(treeUuid, personUuid)
            PersonRepository.ancestorsOf(personUuid, treeUuid)
        }.map(ViewerPolicy(tree, callerAccountId)::project)
    }

    /** 404 when the anchor isn't in [treeId], rather than an empty list that hides the mistake. */
    suspend fun descendantsOf(treeId: TreeId, personId: PersonId, callerAccountId: AccountId): List<PersonDto> {
        val tree = TreeService.getTreeForReader(treeId, callerAccountId)
        val treeUuid = Uuid.parse(treeId.value)
        val personUuid = parseUuidField("personId", personId.value)
        return withContext(DatabaseDispatcher) {
            TreeScopedLookups.requirePerson(treeUuid, personUuid)
            PersonRepository.descendantsOf(personUuid, treeUuid)
        }.map(ViewerPolicy(tree, callerAccountId)::project)
    }
}
