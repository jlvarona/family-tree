package com.moxietech.family_tree.media

/** What an upload is for (FT-008). An upload can only be attached where its purpose allows. */
enum class MediaPurpose {
    /** A person's profile photo (`person.profile_media_id`). */
    PROFILE_PHOTO,

    /** The scan of a source attached to one citation (`citation.media_id`). */
    CITATION_SCAN,
}

/** The lifecycle of an upload; `READY` and `REJECTED` are final. */
enum class MediaStatus {
    PENDING,
    READY,
    REJECTED,
}

/** The image formats accepted in FT-008. */
enum class ImageFormat(val contentType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
}

/**
 * Upload limits shared by the server (which enforces them) and clients (which check
 * first, so the owner gets a localized message before anything is sent).
 */
object MediaRules {
    /** 20 MiB: comfortably above a phone photo, small enough to verify in memory. */
    const val MAX_UPLOAD_BYTES: Long = 20L * 1024 * 1024

    /** Decoded size cap (pixels), so a small file can't claim a huge canvas. */
    const val MAX_PIXELS: Long = 50_000_000

    /** Longest side, in pixels. */
    const val MAX_DIMENSION: Int = 12_000

    val SUPPORTED_CONTENT_TYPES: List<String> = ImageFormat.entries.map { it.contentType }

    /** The format for a declared MIME type (case-insensitive, parameters not allowed), or null. */
    fun formatOf(contentType: String): ImageFormat? =
        ImageFormat.entries.firstOrNull { it.contentType.equals(contentType.trim(), ignoreCase = true) }

    fun purposeOf(name: String): MediaPurpose? = MediaPurpose.entries.firstOrNull { it.name == name }

    fun isSizeAllowed(byteSize: Long): Boolean = byteSize in 1..MAX_UPLOAD_BYTES

    fun areDimensionsAllowed(width: Int, height: Int): Boolean =
        width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION && width.toLong() * height <= MAX_PIXELS
}
