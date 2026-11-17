package com.moxietech.family_tree.server.media

import com.moxietech.family_tree.api.MediaDto
import com.moxietech.family_tree.media.ImageFormat
import com.moxietech.family_tree.media.MediaPurpose
import com.moxietech.family_tree.media.MediaStatus
import com.moxietech.family_tree.server.db.MediaTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import kotlin.uuid.Uuid

/** One `media` row (V6). Storage keys stay inside the server; [toDto] leaves them out. */
internal data class MediaRecord(
    val id: Uuid,
    val treeId: Uuid,
    val purpose: MediaPurpose,
    val status: MediaStatus,
    val format: ImageFormat,
    val uploadByteSize: Long,
    val uploadKey: String,
    val storageKey: String?,
    val byteSize: Long?,
    val width: Int?,
    val height: Int?,
    val sha256: String?,
    val uploadedByAccountId: Uuid,
    val version: Long,
)

internal fun MediaRecord.toDto() = MediaDto(
    id = id.toString(),
    treeId = treeId.toString(),
    purpose = purpose.name,
    status = status.name,
    contentType = format.contentType,
    byteSize = byteSize,
    width = width,
    height = height,
    version = version,
)

/**
 * Tree-scoped access to `media`. Every lookup filters by `tree_id`: another tree's
 * media ID reads as missing, like every other record (FT-065).
 */
internal object MediaRepository {

    fun insert(
        id: Uuid,
        treeId: Uuid,
        purpose: MediaPurpose,
        format: ImageFormat,
        uploadByteSize: Long,
        uploadKey: String,
        uploadedByAccountId: Uuid,
    ): MediaRecord = transaction {
        MediaTable.insert {
            it[MediaTable.id] = id
            it[MediaTable.treeId] = treeId
            it[MediaTable.purpose] = purpose.name
            it[MediaTable.status] = MediaStatus.PENDING.name
            it[MediaTable.contentType] = format.contentType
            it[MediaTable.uploadByteSize] = uploadByteSize
            it[MediaTable.uploadKey] = uploadKey
            it[MediaTable.uploadedByAccountId] = uploadedByAccountId
            it[MediaTable.createdAt] = Instant.now()
            it[MediaTable.version] = 1
        }
        find(treeId, id) ?: error("Media $id was just inserted but cannot be read back")
    }

    /** Null when [id] doesn't exist or belongs to another tree. */
    fun find(treeId: Uuid, id: Uuid): MediaRecord? = transaction {
        MediaTable.selectAll().where { (MediaTable.id eq id) and (MediaTable.treeId eq treeId) }.firstOrNull()?.toRecord()
    }

    /**
     * [find] locking the row `FOR UPDATE` until the transaction ends. Finalization takes
     * it before changing the status, and attaching takes it before checking the status
     * and whether the media is already in use, so those checks can't race.
     */
    fun lockForUpdate(treeId: Uuid, id: Uuid): MediaRecord? = transaction {
        MediaTable.selectAll().where { (MediaTable.id eq id) and (MediaTable.treeId eq treeId) }
            .forUpdate(ForUpdateOption.ForUpdate)
            .firstOrNull()?.toRecord()
    }

    /** PENDING → READY with the cleaned image's details; null (nothing changed) unless still PENDING at [expectedVersion]. */
    fun markReady(
        treeId: Uuid,
        id: Uuid,
        expectedVersion: Long,
        storageKey: String,
        byteSize: Long,
        width: Int,
        height: Int,
        sha256: String,
    ): MediaRecord? = transaction {
        val updated = MediaTable.update({ pendingAt(treeId, id, expectedVersion) }) {
            it[MediaTable.status] = MediaStatus.READY.name
            it[MediaTable.storageKey] = storageKey
            it[MediaTable.byteSize] = byteSize
            it[MediaTable.width] = width
            it[MediaTable.height] = height
            it[MediaTable.sha256] = sha256
            it[MediaTable.finalizedAt] = Instant.now()
            it[MediaTable.version] = expectedVersion + 1
        }
        if (updated == 0) null else find(treeId, id)
    }

    /** PENDING → REJECTED; null (nothing changed) unless still PENDING at [expectedVersion]. */
    fun markRejected(treeId: Uuid, id: Uuid, expectedVersion: Long): MediaRecord? = transaction {
        val updated = MediaTable.update({ pendingAt(treeId, id, expectedVersion) }) {
            it[MediaTable.status] = MediaStatus.REJECTED.name
            it[MediaTable.finalizedAt] = Instant.now()
            it[MediaTable.version] = expectedVersion + 1
        }
        if (updated == 0) null else find(treeId, id)
    }

    private fun pendingAt(treeId: Uuid, id: Uuid, version: Long) =
        (MediaTable.id eq id) and (MediaTable.treeId eq treeId) and
            (MediaTable.status eq MediaStatus.PENDING.name) and (MediaTable.version eq version)

    private fun ResultRow.toRecord() = MediaRecord(
        id = this[MediaTable.id],
        treeId = this[MediaTable.treeId],
        purpose = MediaPurpose.valueOf(this[MediaTable.purpose]),
        status = MediaStatus.valueOf(this[MediaTable.status]),
        format = ImageFormat.entries.single { it.contentType == this[MediaTable.contentType] },
        uploadByteSize = this[MediaTable.uploadByteSize],
        uploadKey = this[MediaTable.uploadKey],
        storageKey = this[MediaTable.storageKey],
        byteSize = this[MediaTable.byteSize],
        width = this[MediaTable.width],
        height = this[MediaTable.height],
        sha256 = this[MediaTable.sha256],
        uploadedByAccountId = this[MediaTable.uploadedByAccountId],
        version = this[MediaTable.version],
    )
}
