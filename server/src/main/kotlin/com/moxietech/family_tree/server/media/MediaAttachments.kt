package com.moxietech.family_tree.server.media

import com.moxietech.family_tree.domain.Person
import com.moxietech.family_tree.media.MediaPurpose
import com.moxietech.family_tree.media.MediaStatus
import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import kotlin.uuid.Uuid

/** 400: the upload was presigned for another purpose (e.g. a citation scan offered as a profile photo). */
const val MEDIA_PURPOSE_MESSAGE = "This upload was made for a different purpose"

fun mediaNotFound(id: Uuid) = NotFoundException("No media with id $id")

fun mediaNotReady(id: Uuid) = ConflictException("Media $id is not a verified upload; finalize it, or upload the image again")

fun mediaInUse(id: Uuid) = ConflictException("Media $id is already attached to another record")

/**
 * Who an image is attached to, which decides who may read it (FT-008). A media row
 * is attached at most once (V6 unique constraints), by its purpose.
 */
sealed interface MediaAttachment {
    /** The profile photo of [person]. */
    data class ProfilePhotoOf(val person: Person) : MediaAttachment

    /** The scan on a citation about [subjects] (`CitationService.subjectsOf`). */
    data class ScanOf(val subjects: List<Person>) : MediaAttachment

    /** Uploaded but not (or no longer) attached: a replaced photo, or one not yet used. */
    data object Unattached : MediaAttachment
}

internal object MediaAttachments {

    /**
     * The checks before attaching [mediaId] as [purpose], inside the caller's write
     * transaction: the media is locked `FOR UPDATE` in [treeId] (missing or another
     * tree's: the generic 404), must be `READY` (409) and must have been uploaded for
     * [purpose] (400). The caller then checks it isn't already attached elsewhere,
     * which the lock keeps stable until commit.
     */
    fun lockForAttaching(treeId: Uuid, mediaId: Uuid, purpose: MediaPurpose): MediaRecord {
        val media = MediaRepository.lockForUpdate(treeId, mediaId) ?: throw mediaNotFound(mediaId)
        if (media.status != MediaStatus.READY) throw mediaNotReady(mediaId)
        if (media.purpose != purpose) throw ValidationException(listOf(MEDIA_PURPOSE_MESSAGE))
        return media
    }
}
