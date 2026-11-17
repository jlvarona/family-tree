package com.moxietech.family_tree.api

import kotlinx.serialization.Serializable

/**
 * FT-008: asks for a presigned upload of one image (owner only).
 *
 * [purpose] is a `com.moxietech.family_tree.media.MediaPurpose` name (`PROFILE_PHOTO`
 * or `CITATION_SCAN`): the upload can later be attached only where that purpose
 * allows. [contentType] must be one of `MediaRules.SUPPORTED_CONTENT_TYPES` and
 * [byteSize] the exact size of the file the client will send (1 byte up to
 * `MediaRules.MAX_UPLOAD_BYTES`). The client never names a storage key.
 */
@Serializable
data class CreateMediaUploadRequest(
    val purpose: String,
    val contentType: String,
    val byteSize: Long,
)

/**
 * One uploaded image as the API describes it. Storage keys and URLs are never
 * part of it. [status] is `PENDING` (presigned, not yet verified: nobody can read
 * it), `READY` (verified and cleaned: readable as its attachment allows) or
 * `REJECTED` (not a supported image, or not the declared size; terminal).
 * [contentType], [byteSize], [width] and [height] describe the stored, cleaned image
 * once `READY`; before that [contentType] is the declared type and the rest are null.
 */
@Serializable
data class MediaDto(
    val id: String,
    val treeId: String,
    val purpose: String,
    val status: String,
    val contentType: String,
    val byteSize: Long?,
    val width: Int?,
    val height: Int?,
    val version: Long,
)

/**
 * Where and how the client sends the bytes: an HTTP [method] (`PUT`) to [url] with
 * exactly [headers] (the ones the signature covers, such as `Content-Type`) and a
 * body of the declared size, before [expiresAt] (ISO-8601 instant). The request goes
 * straight to the object store, without the API's bearer token.
 */
@Serializable
data class PresignedUploadDto(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val expiresAt: String,
)

/** The pending media record and its presigned upload. Finalize the media after the upload. */
@Serializable
data class MediaUploadDto(
    val media: MediaDto,
    val upload: PresignedUploadDto,
)

/** A short-lived, read-only URL for one `READY` image the caller may see, valid until [expiresAt]. */
@Serializable
data class MediaDownloadDto(
    val mediaId: String,
    val url: String,
    val contentType: String,
    val expiresAt: String,
)

/**
 * FT-008: sets ([mediaId]) or removes (null) a person's profile photo. [version] is the
 * person's version the client last read (FT-001); the media must be a `READY`
 * `PROFILE_PHOTO` upload of the same tree that isn't another person's photo.
 */
@Serializable
data class SetProfilePhotoRequest(
    val mediaId: String?,
    val version: Long,
)
