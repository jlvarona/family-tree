package com.moxietech.family_tree.server.media

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FT-008 review fix: the local bootstrap (create the bucket, replace its CORS rules)
 * only runs against an explicit S3-compatible endpoint, never the AWS default endpoint.
 * No containers and no network: both guards fail before any request.
 */
class MediaSettingsTest {

    @Test
    fun `bootstrap without an explicit endpoint is refused at startup`() {
        val error = assertFailsWith<IllegalArgumentException> {
            MediaSettings(endpoint = null, createBucket = true)
        }
        assertTrue("MEDIA_S3_ENDPOINT" in error.message.orEmpty())
        assertFailsWith<IllegalArgumentException> { MediaSettings(localCorsOrigins = listOf("*")) }
        // AWS without the bootstrap, and the local endpoint with it, are both fine.
        MediaSettings(endpoint = null, createBucket = false)
        MediaSettings(endpoint = "http://127.0.0.1:8333", createBucket = true)
    }

    @Test
    fun `the store refuses bootstrap calls without an explicit endpoint`() {
        val aws = MediaSettings(endpoint = null, createBucket = false)
        S3ObjectStore(aws, software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider.create()).use { store ->
            assertFailsWith<IllegalStateException> { runBlocking { store.ensureBucket() } }
            assertFailsWith<IllegalStateException> { runBlocking { store.putLocalCors(listOf("http://localhost:3000")) } }
        }
    }
}
