package com.moxietech.family_tree.server.media

import java.time.Duration

/**
 * Photo storage settings, all from environment variables (README "Photo storage").
 * The defaults point at the local SeaweedFS `s3` service in docker-compose, as the
 * database defaults point at its Postgres. Credentials are not settings: the AWS
 * SDK's default chain supplies them (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`
 * locally, the task role in AWS).
 *
 * For Amazon S3 set `MEDIA_S3_ENDPOINT` to an empty value (the SDK then uses the
 * regional endpoint and virtual-hosted URLs), and `MEDIA_S3_BUCKET` /
 * `MEDIA_S3_REGION` to the deployed bucket. AWS bucket, IAM and deployment
 * configuration are set up separately (decisions table, FT-008).
 */
data class MediaSettings(
    val bucket: String = env("MEDIA_S3_BUCKET") ?: "family-tree-media",
    val region: String = env("MEDIA_S3_REGION") ?: "us-east-1",
    /** The S3 API endpoint the server calls; null for AWS's regional endpoint. */
    val endpoint: String? = (env("MEDIA_S3_ENDPOINT") ?: "http://127.0.0.1:8333").ifBlank { null },
    /**
     * The endpoint presigned URLs name, which the browser must reach. Defaults to
     * [endpoint]; set it when the server reaches the store by another host name.
     */
    val publicEndpoint: String? = env("MEDIA_S3_PUBLIC_ENDPOINT")?.ifBlank { null } ?: endpoint,
    /** Path-style URLs (`host/bucket/key`), which S3-compatible services usually need. */
    val pathStyle: Boolean = env("MEDIA_S3_PATH_STYLE")?.toBooleanStrictOrNull() ?: (endpoint != null),
    /**
     * Local development only: at startup, create the bucket if it's missing and, when
     * [localCorsOrigins] is set, put its CORS rules through the S3 API (README
     * "Photo storage"). Deployed buckets are configured outside the app.
     */
    val createBucket: Boolean = env("MEDIA_S3_CREATE_BUCKET")?.toBooleanStrictOrNull() ?: false,
    /** Browser origins allowed to `PUT` uploads and `GET` images (with [createBucket]), e.g. `http://localhost:3000`. */
    val localCorsOrigins: List<String> = env("MEDIA_S3_CORS_ORIGINS").orEmpty()
        .split(',').map { it.trim() }.filter { it.isNotEmpty() },
    /** How many image verifications may run at once; each decodes a whole image. */
    val maxConcurrentVerifications: Int = env("MEDIA_MAX_CONCURRENT_VERIFICATIONS")?.toIntOrNull()?.coerceAtLeast(1) ?: 2,
    /** How long a presigned upload stays valid. */
    val uploadUrlTtl: Duration = Duration.ofMinutes(10),
    /** How long a presigned download stays valid; a URL can't be revoked before then. */
    val downloadUrlTtl: Duration = Duration.ofMinutes(5),
) {
    init {
        require(bucket.isNotBlank()) { "MEDIA_S3_BUCKET must not be blank" }
        require(region.isNotBlank()) { "MEDIA_S3_REGION must not be blank" }
        require(localCorsOrigins.none { it == "*" }) { "MEDIA_S3_CORS_ORIGINS must name origins, not *" }
    }
}

private fun env(name: String): String? = System.getenv(name)
