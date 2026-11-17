package com.moxietech.family_tree.server.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CORSConfiguration
import software.amazon.awssdk.services.s3.model.CORSRule
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Duration

/**
 * [ObjectStore] over the AWS SDK for Java v2: Amazon S3 in deployment, or any
 * S3-compatible service that checks SigV4 signatures (the local SeaweedFS `s3`
 * service in docker-compose and the media tests) when [MediaSettings.endpoint] is set.
 *
 * Credentials come from the SDK's default chain (environment, profile, or the
 * ECS task role in AWS); nothing is read from the repository. Checksums are only
 * sent where S3 requires them, which keeps S3-compatible services that don't
 * support the newer default integrity headers working.
 */
class S3ObjectStore(
    private val settings: MediaSettings,
    credentials: AwsCredentialsProvider = DefaultCredentialsProvider.builder().build(),
) : ObjectStore, AutoCloseable {

    private val region = Region.of(settings.region)

    private val client: S3Client = S3Client.builder()
        .region(region)
        .credentialsProvider(credentials)
        .forcePathStyle(settings.pathStyle)
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .apply { settings.endpoint?.let { endpointOverride(URI.create(it)) } }
        .build()

    /** Signs URLs for the host the browser uses ([MediaSettings.publicEndpoint]). */
    private val presigner: S3Presigner = S3Presigner.builder()
        .region(region)
        .credentialsProvider(credentials)
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(settings.pathStyle).build())
        .apply { settings.publicEndpoint?.let { endpointOverride(URI.create(it)) } }
        .build()

    override suspend fun presignUpload(key: String, contentType: String, byteSize: Long, ttl: Duration): PresignedUpload = io {
        val put = PutObjectRequest.builder()
            .bucket(settings.bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(byteSize)
            .build()
        val presigned = presigner.presignPutObject(
            PutObjectPresignRequest.builder().signatureDuration(ttl).putObjectRequest(put).build(),
        )
        // The browser sets Host and Content-Length itself (and may not set them by hand).
        val headers = presigned.signedHeaders()
            .filterKeys { !it.equals("host", ignoreCase = true) && !it.equals("content-length", ignoreCase = true) }
            .mapValues { (_, values) -> values.joinToString(",") }
        PresignedUpload(presigned.url().toString(), headers, presigned.expiration())
    }

    override suspend fun presignDownload(key: String, contentType: String, ttl: Duration): PresignedDownload = io {
        val get = GetObjectRequest.builder()
            .bucket(settings.bucket)
            .key(key)
            .responseContentType(contentType)
            .responseContentDisposition("inline")
            .responseCacheControl("private, max-age=${ttl.seconds}")
            .build()
        val presigned = presigner.presignGetObject(
            GetObjectPresignRequest.builder().signatureDuration(ttl).getObjectRequest(get).build(),
        )
        PresignedDownload(presigned.url().toString(), presigned.expiration())
    }

    override suspend fun read(key: String, maxBytes: Long): StoredBytes = io {
        try {
            readCapped(key, maxBytes)
        } catch (e: NoSuchKeyException) {
            StoredBytes.Missing
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) StoredBytes.Missing else throw e
        }
    }

    private fun readCapped(key: String, maxBytes: Long): StoredBytes {
        val request = GetObjectRequest.builder().bucket(settings.bucket).key(key).build()
        client.getObject(request).use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    // Don't drain the rest of an oversized object.
                    stream.abort()
                    return StoredBytes.TooLarge
                }
                out.write(buffer, 0, n)
            }
            return StoredBytes.Found(out.toByteArray())
        }
    }

    override suspend fun write(key: String, bytes: ByteArray, contentType: String) = io {
        val put = PutObjectRequest.builder()
            .bucket(settings.bucket)
            .key(key)
            .contentType(contentType)
            .contentLength(bytes.size.toLong())
            .build()
        client.putObject(put, RequestBody.fromBytes(bytes))
        Unit
    }

    override suspend fun delete(key: String) = io {
        client.deleteObject(DeleteObjectRequest.builder().bucket(settings.bucket).key(key).build())
        Unit
    }

    /**
     * Local development only (`MEDIA_S3_CREATE_BUCKET=true`): creates the bucket when
     * it doesn't exist. Deployed buckets are created and configured outside the app.
     */
    suspend fun ensureBucket() = io {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(settings.bucket).build())
        } catch (e: S3Exception) {
            if (e.statusCode() != 404) throw e
            client.createBucket(CreateBucketRequest.builder().bucket(settings.bucket).build())
        }
        Unit
    }

    /**
     * Local development only: lets [origins] `PUT` presigned uploads (sending only the
     * signed `Content-Type`) and `GET` presigned images from the browser. Replaces the
     * bucket's CORS rules. Deployment sets the same rules on the S3 bucket separately.
     */
    suspend fun putLocalCors(origins: List<String>) = io {
        if (origins.isEmpty()) return@io
        val rule = CORSRule.builder()
            .allowedOrigins(origins)
            .allowedMethods("PUT", "GET")
            .allowedHeaders("content-type")
            .exposeHeaders("ETag")
            .maxAgeSeconds(600)
            .build()
        client.putBucketCors(
            PutBucketCorsRequest.builder()
                .bucket(settings.bucket)
                .corsConfiguration(CORSConfiguration.builder().corsRules(rule).build())
                .build(),
        )
        Unit
    }

    override fun close() {
        presigner.close()
        client.close()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: SdkException) {
            throw ObjectStoreUnavailableException(e)
        }
    }
}
