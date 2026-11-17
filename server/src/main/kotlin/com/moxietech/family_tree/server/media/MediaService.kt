package com.moxietech.family_tree.server.media

import com.moxietech.family_tree.api.CreateMediaUploadRequest
import com.moxietech.family_tree.api.MediaDownloadDto
import com.moxietech.family_tree.api.MediaDto
import com.moxietech.family_tree.api.MediaUploadDto
import com.moxietech.family_tree.api.PresignedUploadDto
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.AuditAction
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.media.MediaPurpose
import com.moxietech.family_tree.media.MediaRules
import com.moxietech.family_tree.media.MediaStatus
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.collaboration.AuditLogRepository
import com.moxietech.family_tree.server.config.DatabaseDispatcher
import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.errors.parseUuidField
import com.moxietech.family_tree.server.graph.PersonRepository
import com.moxietech.family_tree.server.graph.ViewerPolicy
import com.moxietech.family_tree.server.sources.CitationRepository
import com.moxietech.family_tree.server.sources.CitationService
import com.moxietech.family_tree.server.trees.TreeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection
import kotlin.uuid.Uuid

const val MEDIA_PURPOSE_UNKNOWN_MESSAGE = "purpose must be PROFILE_PHOTO or CITATION_SCAN"
const val MEDIA_CONTENT_TYPE_MESSAGE = "contentType must be image/jpeg or image/png"
const val MEDIA_BYTE_SIZE_MESSAGE = "byteSize must be between 1 and 20971520 bytes"
const val MEDIA_UPLOAD_INCOMPLETE_MESSAGE = "The upload hasn't arrived yet; send the file to the upload URL, then finalize again"
const val MEDIA_REJECTED_MESSAGE = "This upload was rejected; upload the image again"
const val MEDIA_SIZE_MISMATCH_MESSAGE = "The uploaded file isn't the size that was declared"
const val MEDIA_INVALID_IMAGE_MESSAGE = "The uploaded file isn't a valid image of the declared type"
const val MEDIA_TOO_LARGE_IMAGE_MESSAGE = "The image has too many pixels"

/**
 * FT-008 photo uploads: presign, finalize and read. The API never carries image
 * bytes: clients `PUT` to a presigned URL and browsers `GET` from one.
 *
 * - **Presign** (owner only): validates purpose, declared type and size, generates
 *   the media ID and both keys server-side, signs a `PUT` for the `uploads/` key,
 *   then inserts the `PENDING` row and its `CREATE` audit in one transaction.
 * - **Finalize** (owner only), safe to retry at any point:
 *   1. read the row in the URL's tree (404); `READY` returns it unchanged (idempotent),
 *      `REJECTED` is a 409;
 *   2. read the upload with a cap; if it's missing, re-read the row: `READY` (an
 *      overlapping finalization won and removed the upload) is returned, otherwise 409
 *      (still `PENDING`: retry after uploading);
 *   3. outside any transaction, check the exact declared size, then verify, strip
 *      metadata ([ImageSanitizer]) and decode every pixel;
 *   4. write the cleaned image under a key holding its SHA-256. Steps 2-4 hold one of
 *      [MediaSettings.maxConcurrentVerifications] permits, so that bounds the image
 *      buffers held at once;
 *   5. one transaction: lock the row, and only if still `PENDING` mark it `READY`
 *      with the stored key and details (or `REJECTED` after step 3), with its audit;
 *   6. delete the upload object, best effort (a bucket lifecycle rule backs it up).
 *   A failure after step 4 leaves the row `PENDING` and only an unreadable object
 *   behind; nothing is ever presigned for reading until step 5 commits.
 * - **Download** (active members): a short-lived `GET` URL for a `READY` image the
 *   caller may see ([ViewerPolicy.canSeeMedia]), decided in one read-only snapshot.
 *   Anything else, including pending and rejected uploads, is the same 404 as a
 *   missing ID.
 */
class MediaService(
    private val storeRef: Lazy<ObjectStore>,
    private val settings: MediaSettings = MediaSettings(),
) {
    private val store get() = storeRef.value
    private val verifications = Semaphore(settings.maxConcurrentVerifications)
    private val log = LoggerFactory.getLogger(MediaService::class.java)

    suspend fun createUpload(treeId: TreeId, callerAccountId: AccountId, request: CreateMediaUploadRequest): MediaUploadDto {
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        val purpose = MediaRules.purposeOf(request.purpose) ?: throw ValidationException(listOf(MEDIA_PURPOSE_UNKNOWN_MESSAGE))
        val format = MediaRules.formatOf(request.contentType) ?: throw ValidationException(listOf(MEDIA_CONTENT_TYPE_MESSAGE))
        if (!MediaRules.isSizeAllowed(request.byteSize)) throw ValidationException(listOf(MEDIA_BYTE_SIZE_MESSAGE))

        val treeUuid = Uuid.parse(tree.id.value)
        val mediaId = Uuid.random()
        val uploadKey = MediaKeys.upload(treeUuid, mediaId)
        // Signing first: if it fails, no row is left behind. A URL whose row never commits
        // can only put an unreadable object under uploads/, which nothing finalizes.
        val presigned = store.presignUpload(uploadKey, format.contentType, request.byteSize, settings.uploadUrlTtl)
        val media = withContext(DatabaseDispatcher) {
            transaction {
                val created = MediaRepository.insert(
                    id = mediaId,
                    treeId = treeUuid,
                    purpose = purpose,
                    format = format,
                    uploadByteSize = request.byteSize,
                    uploadKey = uploadKey,
                    uploadedByAccountId = Uuid.parse(callerAccountId.value),
                )
                audit(treeUuid, callerAccountId, created, AuditAction.CREATE, before = null)
                created
            }
        }
        return MediaUploadDto(
            media = media.toDto(),
            upload = PresignedUploadDto(presigned.url, "PUT", presigned.headers, presigned.expiresAt.toString()),
        )
    }

    suspend fun finalize(treeId: TreeId, rawMediaId: String, callerAccountId: AccountId): MediaDto {
        val tree = TreeService.getTreeForWriter(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val mediaId = parseUuidField("mediaId", rawMediaId)
        val media = withContext(DatabaseDispatcher) { MediaRepository.find(treeUuid, mediaId) } ?: throw mediaNotFound(mediaId)
        when (media.status) {
            MediaStatus.READY -> return media.toDto()
            MediaStatus.REJECTED -> throw ConflictException(MEDIA_REJECTED_MESSAGE)
            MediaStatus.PENDING -> Unit
        }

        // One permit covers every image buffer this request holds: the upload read (up to
        // 20 MiB), the verification and the write of the cleaned copy. Only the stored
        // image's details leave the block, so the buffers are unreachable once the permit
        // is released and at most maxConcurrentVerifications sets exist at once.
        val stored = verifications.withPermit {
            val bytes = when (val read = store.read(media.uploadKey, maxBytes = media.uploadByteSize)) {
                // Not uploaded yet, or an overlapping finalization finished and removed it:
                // the row decides which.
                StoredBytes.Missing -> {
                    val current = withContext(DatabaseDispatcher) { MediaRepository.find(treeUuid, mediaId) } ?: throw mediaNotFound(mediaId)
                    when (current.status) {
                        MediaStatus.READY -> return current.toDto()
                        MediaStatus.REJECTED -> throw ConflictException(MEDIA_REJECTED_MESSAGE)
                        MediaStatus.PENDING -> throw ConflictException(MEDIA_UPLOAD_INCOMPLETE_MESSAGE)
                    }
                }
                StoredBytes.TooLarge -> return reject(treeUuid, callerAccountId, media, MEDIA_SIZE_MISMATCH_MESSAGE)
                is StoredBytes.Found -> read.bytes
            }
            if (bytes.size.toLong() != media.uploadByteSize) return reject(treeUuid, callerAccountId, media, MEDIA_SIZE_MISMATCH_MESSAGE)

            val image = when (val result = withContext(Dispatchers.Default) { ImageSanitizer.sanitize(bytes, media.format) }) {
                is SanitizeResult.Invalid -> return reject(
                    treeUuid,
                    callerAccountId,
                    media,
                    if (result.reason == InvalidImage.TOO_LARGE) MEDIA_TOO_LARGE_IMAGE_MESSAGE else MEDIA_INVALID_IMAGE_MESSAGE,
                )
                is SanitizeResult.Clean -> result.image
            }
            val sha256 = sha256Hex(image.bytes)
            val key = MediaKeys.stored(treeUuid, mediaId, sha256)
            store.write(key, image.bytes, image.format.contentType)
            StoredImage(key, sha256, image.bytes.size.toLong(), image.width, image.height)
        }
        val storageKey = stored.key

        val ready = withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val locked = MediaRepository.lockForUpdate(treeUuid, mediaId) ?: throw mediaNotFound(mediaId)
                when (locked.status) {
                    // An overlapping finalization committed first; its own stored object wins.
                    MediaStatus.READY -> locked
                    MediaStatus.REJECTED -> throw ConflictException(MEDIA_REJECTED_MESSAGE)
                    MediaStatus.PENDING -> {
                        val updated = MediaRepository.markReady(
                            treeId = treeUuid,
                            id = mediaId,
                            expectedVersion = locked.version,
                            storageKey = storageKey,
                            byteSize = stored.byteSize,
                            width = stored.width,
                            height = stored.height,
                            sha256 = stored.sha256,
                        ) ?: error("Media $mediaId was locked PENDING but could not be marked READY")
                        audit(treeUuid, callerAccountId, updated, AuditAction.UPDATE, before = locked)
                        updated
                    }
                }
            }
        }
        if (ready.storageKey != storageKey) deleteQuietly(storageKey)
        deleteQuietly(media.uploadKey)
        return ready.toDto()
    }

    suspend fun download(treeId: TreeId, rawMediaId: String, callerAccountId: AccountId): MediaDownloadDto {
        val tree = TreeService.getTreeForReader(treeId, callerAccountId)
        val treeUuid = Uuid.parse(tree.id.value)
        val mediaId = parseUuidField("mediaId", rawMediaId)
        val policy = ViewerPolicy(tree, callerAccountId)
        val media = withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
                val media = MediaRepository.find(treeUuid, mediaId)?.takeIf { it.status == MediaStatus.READY }
                    ?: throw mediaNotFound(mediaId)
                if (!policy.canSeeMedia(attachmentOf(treeUuid, media))) throw mediaNotFound(mediaId)
                media
            }
        }
        val key = media.storageKey ?: error("READY media $mediaId has no storage key")
        val presigned = store.presignDownload(key, media.format.contentType, settings.downloadUrlTtl)
        return MediaDownloadDto(media.id.toString(), presigned.url, media.format.contentType, presigned.expiresAt.toString())
    }

    /** What [media] is attached to, by its purpose (each media row is attached at most once, V6). */
    private fun attachmentOf(treeId: Uuid, media: MediaRecord): MediaAttachment = when (media.purpose) {
        MediaPurpose.PROFILE_PHOTO ->
            PersonRepository.findByProfileMedia(treeId, media.id)?.let { MediaAttachment.ProfilePhotoOf(it) }
        MediaPurpose.CITATION_SCAN ->
            CitationRepository.findByMedia(treeId, media.id)?.let { citation ->
                MediaAttachment.ScanOf(CitationService.subjectsOf(treeId, CitationRepository.targetOf(citation)))
            }
    } ?: MediaAttachment.Unattached

    /**
     * Marks a still-PENDING upload REJECTED (audited), removes its object, and answers 400.
     * If an overlapping finalization already made it READY from a valid upload, that
     * result is returned instead.
     */
    private suspend fun reject(treeId: Uuid, caller: AccountId, media: MediaRecord, message: String): MediaDto {
        val current = withContext(DatabaseDispatcher) {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                val locked = MediaRepository.lockForUpdate(treeId, media.id) ?: throw mediaNotFound(media.id)
                if (locked.status != MediaStatus.PENDING) return@transaction locked
                val rejected = MediaRepository.markRejected(treeId, media.id, locked.version)
                    ?: error("Media ${media.id} was locked PENDING but could not be marked REJECTED")
                audit(treeId, caller, rejected, AuditAction.UPDATE, before = locked)
                rejected
            }
        }
        deleteQuietly(media.uploadKey)
        if (current.status == MediaStatus.READY) return current.toDto()
        throw ValidationException(listOf(message))
    }

    private fun audit(treeId: Uuid, actor: AccountId, after: MediaRecord, action: AuditAction, before: MediaRecord?) =
        AuditLogRepository.append(
            treeId = treeId,
            actorAccountId = Uuid.parse(actor.value),
            entityType = "Media",
            entityId = after.id.toString(),
            action = action,
            beforeJson = before?.let { AuditJson.encodeToString(it.toDto()) },
            afterJson = AuditJson.encodeToString(after.toDto()),
        )

    /** Cleanup that must never fail the request: the row already says what is readable. */
    private suspend fun deleteQuietly(key: String) {
        try {
            store.delete(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("Could not delete an unreadable media object; the bucket lifecycle rule will remove it")
        }
    }

    /** What finalization keeps of a stored image once its buffers are released. */
    private class StoredImage(val key: String, val sha256: String, val byteSize: Long, val width: Int, val height: Int)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
