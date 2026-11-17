package com.moxietech.family_tree.server.media

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.api.CreateCitationRequest
import com.moxietech.family_tree.api.CreateMediaUploadRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.api.CreateSourceRequest
import com.moxietech.family_tree.api.MediaDownloadDto
import com.moxietech.family_tree.api.MediaDto
import com.moxietech.family_tree.api.MediaUploadDto
import com.moxietech.family_tree.api.MergePeopleRequest
import com.moxietech.family_tree.api.MergePeopleResponse
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.SetProfilePhotoRequest
import com.moxietech.family_tree.api.SourceDto
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.module
import com.moxietech.family_tree.server.config.ApiErrorResponse
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DEFAULT_API_AUDIENCE
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.config.OidcSettings
import com.moxietech.family_tree.server.config.STORAGE_UNAVAILABLE_MESSAGE
import com.moxietech.family_tree.server.config.TestJwks
import com.moxietech.family_tree.server.identity.IdentityTestApi.bodyWithStatus
import com.moxietech.family_tree.server.identity.IdentityTestApi.createTree
import com.moxietech.family_tree.server.identity.IdentityTestApi.me
import com.moxietech.family_tree.server.identity.IdentityTestApi.query
import com.moxietech.family_tree.server.identity.IdentityTestApi.uuid
import com.moxietech.family_tree.server.trees.MembershipRepository
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import java.net.http.HttpClient as JdkHttpClient

/**
 * FT-008 photo uploads end to end: HTTP through the production [module] and token
 * verifier, Testcontainers PostgreSQL 16 migrated by Flyway, and a real S3-compatible
 * store ([MediaTestStore]) that checks presigned signatures. Image bytes go from the
 * test straight to the store's presigned URLs, never through the API.
 *
 * Actors: OWNER owns tree A; RELATIVE is an active non-owner member of tree A (living
 * people are hidden from them); INVITEE holds only an invitation; OTHER_OWNER owns tree B.
 */
class MediaRoutesTest {

    private enum class Actor(val subject: String, val email: String) {
        OWNER("ft008-owner", "ft008-owner@example.com"),
        OTHER_OWNER("ft008-other", "ft008-other@example.com"),
        RELATIVE("ft008-relative", "ft008-relative@example.com"),
        INVITEE("ft008-invitee", "ft008-invitee@example.com"),
    }

    /** Delegates to the real store, failing chosen calls to simulate an outage mid-finalization. */
    private class FaultInjectingStore(private val delegate: ObjectStore) : ObjectStore {
        private val nextReadGate = AtomicReference<ReadGate?>()

        @Volatile var failNextWrite = false

        @Volatile var failDeletes = false

        fun delayNextRead(): ReadGate = ReadGate().also { check(nextReadGate.compareAndSet(null, it)) }

        override suspend fun presignUpload(key: String, contentType: String, byteSize: Long, ttl: Duration) =
            delegate.presignUpload(key, contentType, byteSize, ttl)

        override suspend fun presignDownload(key: String, contentType: String, ttl: Duration) =
            delegate.presignDownload(key, contentType, ttl)

        override suspend fun read(key: String, maxBytes: Long): StoredBytes {
            nextReadGate.getAndSet(null)?.let { gate ->
                gate.waiting.complete(Unit)
                gate.resume.await()
            }
            return delegate.read(key, maxBytes)
        }

        override suspend fun write(key: String, bytes: ByteArray, contentType: String) {
            if (failNextWrite) {
                failNextWrite = false
                throw ObjectStoreUnavailableException()
            }
            delegate.write(key, bytes, contentType)
        }

        override suspend fun delete(key: String) {
            if (failDeletes) throw ObjectStoreUnavailableException()
            delegate.delete(key)
        }
    }

    private class ReadGate {
        val waiting = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
    }

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val ISSUER = "http://keycloak.test/realms/family-tree"
        private const val ORIGIN = "http://localhost:3000"
        private val json = Json { ignoreUnknownKeys = true }
        private val http: JdkHttpClient = JdkHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        private lateinit var dataSource: HikariDataSource
        private lateinit var jwks: TestJwks
        private lateinit var objectStore: MediaTestStore
        private lateinit var store: S3ObjectStore
        private lateinit var faulty: FaultInjectingStore
        private lateinit var media: MediaService
        private lateinit var accounts: Map<Actor, AccountDto>
        private lateinit var treeA: String
        private lateinit var treeB: String

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dataSource = DatabaseConfig.createDataSource(
                DatabaseSettings(jdbcUrl = postgres.jdbcUrl, user = postgres.username, password = postgres.password),
            )
            DatabaseConfig.migrate(dataSource)
            DatabaseConfig.connectExposed(dataSource)
            AppDatabase.init(dataSource)
            jwks = TestJwks()
            objectStore = MediaTestStore().start()
            store = objectStore.newStore()
            runBlocking { store.putLocalCors(listOf(ORIGIN)) }
            faulty = FaultInjectingStore(store)
            media = MediaService(lazyOf(faulty), objectStore.settings)
            testApplication {
                application { module(settings(), media) }
                accounts = Actor.entries.associateWith { client.me(token(it)) }
                treeA = client.createTree(token(Actor.OWNER), "FT-008 media A").id
                treeB = client.createTree(token(Actor.OTHER_OWNER), "FT-008 media B").id
                MembershipRepository.insert(
                    Uuid.parse(treeA), Uuid.parse(accounts.getValue(Actor.RELATIVE).id), personId = null, status = MembershipStatus.ACTIVE,
                )
                MembershipRepository.insert(
                    Uuid.parse(treeA), Uuid.parse(accounts.getValue(Actor.INVITEE).id), personId = null, status = MembershipStatus.INVITED,
                )
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::store.isInitialized) store.close()
            if (::objectStore.isInitialized) objectStore.close()
            if (::jwks.isInitialized) jwks.close()
            if (::dataSource.isInitialized) dataSource.close()
        }

        private fun settings() = OidcSettings(issuer = ISSUER, jwksUri = jwks.jwksUri, audience = DEFAULT_API_AUDIENCE)

        private fun token(actor: Actor): String = jwks.accessToken(ISSUER, email = actor.email) { withSubject(actor.subject) }
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        faulty.failNextWrite = false
        faulty.failDeletes = false
        application { module(settings(), media) }
        block()
    }

    // --- HTTP helpers ---------------------------------------------------------------------

    private inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    private suspend inline fun <reified T> HttpResponse.decoded(status: HttpStatusCode = HttpStatusCode.OK): T =
        json.decodeFromString(bodyWithStatus(status))

    private suspend fun HttpClient.send(method: HttpMethod, path: String, actor: Actor, body: String? = null): HttpResponse =
        request(path) {
            this.method = method
            bearerAuth(token(actor))
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private fun t(tree: String) = "/api/v1/trees/$tree"

    private suspend fun HttpClient.presign(
        tree: String,
        actor: Actor = Actor.OWNER,
        purpose: String = "PROFILE_PHOTO",
        type: String = "image/jpeg",
        size: Long,
    ) = send(HttpMethod.Post, "${t(tree)}/media", actor, encode(CreateMediaUploadRequest(purpose, type, size)))

    private suspend fun HttpClient.finalize(tree: String, id: String, actor: Actor = Actor.OWNER) =
        send(HttpMethod.Post, "${t(tree)}/media/$id/finalize", actor)

    private suspend fun HttpClient.download(tree: String, id: String, actor: Actor) =
        send(HttpMethod.Get, "${t(tree)}/media/$id/download", actor)

    private suspend fun HttpClient.setPhoto(tree: String, person: PersonDto, mediaId: String?, actor: Actor = Actor.OWNER, version: Long = person.version) =
        send(HttpMethod.Put, "${t(tree)}/people/${person.id}/profile-photo", actor, encode(SetProfilePhotoRequest(mediaId, version)))

    private suspend fun HttpClient.person(tree: String, given: String, living: Boolean = false, actor: Actor = Actor.OWNER): PersonDto =
        send(HttpMethod.Post, "${t(tree)}/people", actor, encode(CreatePersonRequest(givenNames = given, sex = "UNKNOWN", isLiving = living)))
            .decoded<CreatePersonResponse>(HttpStatusCode.Created).person

    private suspend fun HttpClient.reload(tree: String, person: PersonDto, actor: Actor = Actor.OWNER): PersonDto =
        send(HttpMethod.Get, "${t(tree)}/people/${person.id}", actor).decoded()

    /** Presigns, uploads [bytes] to the store with the signed headers, and returns the pending upload. */
    private suspend fun HttpClient.uploaded(
        tree: String,
        bytes: ByteArray,
        type: String = "image/jpeg",
        purpose: String = "PROFILE_PHOTO",
        actor: Actor = Actor.OWNER,
    ): MediaUploadDto {
        val upload = presign(tree, actor, purpose, type, bytes.size.toLong()).decoded<MediaUploadDto>(HttpStatusCode.Created)
        assertEquals(200, put(upload, bytes), "presigned PUT")
        return upload
    }

    private suspend fun HttpClient.ready(
        tree: String,
        bytes: ByteArray = TestImages.jpegWithGps(),
        type: String = "image/jpeg",
        purpose: String = "PROFILE_PHOTO",
        actor: Actor = Actor.OWNER,
    ): MediaDto = finalize(tree, uploaded(tree, bytes, type, purpose, actor).media.id, actor).decoded()

    /** A presigned PUT straight to the object store, as a browser sends it. */
    private fun put(upload: MediaUploadDto, bytes: ByteArray, headers: Map<String, String> = upload.upload.headers, url: String = upload.upload.url): Int {
        assertEquals("PUT", upload.upload.method)
        val request = HttpRequest.newBuilder(URI(url)).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
        headers.forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), BodyHandlers.discarding()).statusCode()
    }

    private fun fetch(url: String): java.net.http.HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI(url)).GET().build(), BodyHandlers.ofByteArray())

    private fun count(sql: String, vararg args: Any?): Long = dataSource.query(sql, *args)!!.toLong()

    private fun audits(tree: String, type: String, id: String): Long =
        count("select count(*) from audit_log where tree_id = ? and entity_type = ? and entity_id = ?", uuid(tree), type, id)

    private fun status(id: String): String? = dataSource.query("select status from media where id = ?", uuid(id))

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, message: String? = null, context: String = "") {
        val body = response.bodyAsText()
        assertEquals(status, response.status, "$context: $body")
        if (message != null) assertEquals(message, json.decodeFromString<ApiErrorResponse>(body).message, context)
        assertFalse("Exception" in body || "SQL" in body || "uploads/" in body || "media/" in body || objectStore.bucket in body, "$context exposed internals: $body")
    }

    private suspend fun awaitBlocked(pattern: String) = withTimeout(10_000) {
        val sql = "select count(*) from pg_stat_activity where pid <> pg_backend_pid() and wait_event_type = 'Lock' and query ilike ?"
        while (count(sql, "%$pattern%") < 1) delay(25)
    }

    // --- Round trip ------------------------------------------------------------------------

    @Test
    fun `an uploaded photo is verified, stripped of GPS, attached and readable only through a short-lived URL`() = api {
        val ana = client.person(treeA, "Ana")
        val original = TestImages.jpegWithGps()
        val upload = client.uploaded(treeA, original)
        assertEquals("PENDING", upload.media.status)
        assertTrue(upload.upload.headers.keys.any { it.equals("content-type", ignoreCase = true) }, "Content-Type is signed")
        assertError(client.download(treeA, upload.media.id, Actor.OWNER), HttpStatusCode.NotFound, "No media with id ${upload.media.id}", "pending")

        val ready = client.finalize(treeA, upload.media.id).decoded<MediaDto>()
        assertEquals("READY", ready.status)
        assertEquals(64 to 48, ready.width to ready.height)
        assertEquals(StoredBytes.Missing, store.read("uploads/$treeA/${ready.id}", Long.MAX_VALUE), "the unverified upload is deleted")
        assertEquals(2, audits(treeA, "Media", ready.id), "CREATE and READY audited")

        val withPhoto = client.setPhoto(treeA, ana, ready.id).decoded<PersonDto>()
        assertEquals(ready.id, withPhoto.profileMediaId)
        assertEquals(ana.version + 1, withPhoto.version)

        // A member who can see Ana reads the cleaned image from the store.
        val link = client.download(treeA, ready.id, Actor.RELATIVE).decoded<MediaDownloadDto>()
        assertEquals("image/jpeg", link.contentType)
        val fetched = fetch(link.url)
        assertEquals(200, fetched.statusCode())
        assertEquals("image/jpeg", fetched.headers().firstValue("content-type").orElse(null))
        val bytes = fetched.body()
        assertFalse(TestImages.contains(bytes, TestImages.GPS_MARKER.toByteArray()), "no GPS in what readers get")
        assertFalse(TestImages.contains(bytes, TestImages.GPS_LATITUDE))
        assertTrue(TestImages.contains(bytes, TestImages.ORIENTATION_6_ENTRY), "orientation kept")
        assertEquals(64, assertNotNull(TestImages.decode(bytes)).width)
    }

    @Test
    fun `the object store enforces the presigned signature and keeps everything private`() = api {
        val bytes = TestImages.jpeg()
        val upload = client.presign(treeA, size = bytes.size.toLong()).decoded<MediaUploadDto>(HttpStatusCode.Created)
        val url = upload.upload.url
        val tampered = url.replace(Regex("X-Amz-Signature=([0-9a-f])")) { m -> "X-Amz-Signature=" + if (m.groupValues[1] == "0") "1" else "0" }
        assertNotEquals(url, tampered)
        assertEquals(403, put(upload, bytes, url = tampered), "tampered signature")
        assertEquals(403, put(upload, bytes, headers = mapOf("Content-Type" to "image/png")), "a different Content-Type than signed")
        assertEquals(StoredBytes.Missing, store.read("uploads/$treeA/${upload.media.id}", Long.MAX_VALUE), "nothing was stored")

        assertEquals(200, put(upload, bytes))
        val ready = client.finalize(treeA, upload.media.id).decoded<MediaDto>()
        val key = dataSource.query("select storage_key from media where id = ?", uuid(ready.id))!!
        val anonymous = fetch("${objectStore.endpoint}/${objectStore.bucket}/$key")
        assertTrue(anonymous.statusCode() in setOf(401, 403), "anonymous read of a stored image: ${anonymous.statusCode()}")
        val signed = client.download(treeA, ready.id, Actor.OWNER).decoded<MediaDownloadDto>()
        assertEquals(200, fetch(signed.url).statusCode())
        val wrongKey = signed.url.replace(ready.id, UUID.randomUUID().toString())
        assertEquals(403, fetch(wrongKey).statusCode(), "a signature covers one key only")
    }

    @Test
    fun `the bucket's CORS rules let the local web origin preflight an upload`() = api {
        val upload = client.presign(treeA, size = 10).decoded<MediaUploadDto>(HttpStatusCode.Created)
        fun preflight(origin: String) = http.send(
            HttpRequest.newBuilder(URI(upload.upload.url))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type")
                .build(),
            BodyHandlers.discarding(),
        )
        val allowed = preflight(ORIGIN)
        assertTrue(allowed.statusCode() in 200..299, "preflight status ${allowed.statusCode()}")
        assertEquals(ORIGIN, allowed.headers().firstValue("access-control-allow-origin").orElse(null))
        assertTrue(
            allowed.headers().firstValue("access-control-allow-methods").orElse("").contains("PUT", ignoreCase = true),
            "PUT allowed",
        )
        val other = preflight("http://evil.example")
        assertNotEquals("http://evil.example", other.headers().firstValue("access-control-allow-origin").orElse(null))
        assertNotEquals("*", other.headers().firstValue("access-control-allow-origin").orElse(null))
    }

    // --- Finalization is retry-safe --------------------------------------------------------

    @Test
    fun `finalize before the upload arrives is a 409 that leaves the media pending, and later succeeds once`() = api {
        val bytes = TestImages.png()
        val upload = client.presign(treeA, type = "image/png", size = bytes.size.toLong()).decoded<MediaUploadDto>(HttpStatusCode.Created)
        assertError(client.finalize(treeA, upload.media.id), HttpStatusCode.Conflict, MEDIA_UPLOAD_INCOMPLETE_MESSAGE)
        assertEquals("PENDING", status(upload.media.id))

        assertEquals(200, put(upload, bytes))
        val ready = client.finalize(treeA, upload.media.id).decoded<MediaDto>()
        val again = client.finalize(treeA, upload.media.id).decoded<MediaDto>()
        assertEquals(ready, again, "a repeated finalize returns the same record")
        assertEquals(2, audits(treeA, "Media", ready.id), "audited once")
    }

    @Test
    fun `an object store failure after verification leaves nothing readable, and the retry completes`() = api {
        val upload = client.uploaded(treeA, TestImages.jpeg())
        faulty.failNextWrite = true
        assertError(client.finalize(treeA, upload.media.id), HttpStatusCode.ServiceUnavailable, STORAGE_UNAVAILABLE_MESSAGE)
        assertEquals("PENDING", status(upload.media.id))
        assertNull(dataSource.query("select storage_key from media where id = ?", uuid(upload.media.id)))
        assertError(client.download(treeA, upload.media.id, Actor.OWNER), HttpStatusCode.NotFound)

        // Cleanup failing afterwards doesn't undo a committed READY.
        faulty.failDeletes = true
        val ready = client.finalize(treeA, upload.media.id).decoded<MediaDto>()
        assertEquals("READY", ready.status)
        faulty.failDeletes = false
        assertEquals(200, fetch(client.download(treeA, ready.id, Actor.OWNER).decoded<MediaDownloadDto>().url).statusCode())
    }

    @Test
    fun `uploads that aren't the declared image are rejected for good and never readable`() = api {
        data class Case(val name: String, val bytes: ByteArray, val type: String, val message: String)
        val cases = listOf(
            Case("JPEG declared as PNG", TestImages.jpeg(), "image/png", MEDIA_INVALID_IMAGE_MESSAGE),
            Case("PNG with a corrupt zlib stream", TestImages.pngWithCorruptIdat(), "image/png", MEDIA_INVALID_IMAGE_MESSAGE),
            Case("JPEG with a truncated scan", TestImages.jpegWithTruncatedScan(), "image/jpeg", MEDIA_INVALID_IMAGE_MESSAGE),
            Case("too many pixels", TestImages.pngClaiming(20_000, 1), "image/png", MEDIA_TOO_LARGE_IMAGE_MESSAGE),
        )
        for (case in cases) {
            val upload = client.uploaded(treeA, case.bytes, case.type)
            assertError(client.finalize(treeA, upload.media.id), HttpStatusCode.BadRequest, case.message, case.name)
            assertEquals("REJECTED", status(upload.media.id), case.name)
            assertError(client.finalize(treeA, upload.media.id), HttpStatusCode.Conflict, MEDIA_REJECTED_MESSAGE, case.name)
            assertError(client.download(treeA, upload.media.id, Actor.OWNER), HttpStatusCode.NotFound, context = case.name)
            assertEquals(StoredBytes.Missing, store.read("uploads/$treeA/${upload.media.id}", Long.MAX_VALUE), case.name)
        }

        // An object that isn't the declared size (written behind the signature's back).
        val sized = client.presign(treeA, size = 1000).decoded<MediaUploadDto>(HttpStatusCode.Created)
        store.write("uploads/$treeA/${sized.media.id}", TestImages.jpeg(), "image/jpeg")
        assertError(client.finalize(treeA, sized.media.id), HttpStatusCode.BadRequest, MEDIA_SIZE_MISMATCH_MESSAGE)
        assertEquals("REJECTED", status(sized.media.id))
    }

    // --- Authorization, validation, tenancy ------------------------------------------------

    @Test
    fun overlappingFinalizeReturnsReadyAfterTheWinnerDeletesStaging() = api {
        val upload = client.uploaded(treeA, TestImages.jpegWithGps())
        val gate = faulty.delayNextRead()
        withTimeout(15_000) {
            coroutineScope {
                val delayed = async { client.finalize(treeA, upload.media.id) }
                gate.waiting.await()
                val winner = try {
                    client.finalize(treeA, upload.media.id).decoded<MediaDto>()
                } finally {
                    gate.resume.complete(Unit)
                }
                assertEquals(winner, delayed.await().decoded<MediaDto>())
            }
        }
        assertEquals(2L, audits(treeA, "Media", upload.media.id))
    }

    @Test
    fun `only the owner presigns, finalizes and assigns, and every input is checked`() = api {
        val ana = client.person(treeA, "Ana")
        val ownerOnly = "Only the tree owner can make changes to tree $treeA"
        val pending = client.presign(treeA, size = 10).decoded<MediaUploadDto>(HttpStatusCode.Created).media.id
        for (actor in listOf(Actor.RELATIVE, Actor.INVITEE, Actor.OTHER_OWNER)) {
            for (body in listOf(encode(CreateMediaUploadRequest("PROFILE_PHOTO", "image/jpeg", 10)), "{not json")) {
                assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Post, "${t(treeA)}/media", actor, body).status, "$actor presign")
            }
            assertEquals(HttpStatusCode.Forbidden, client.finalize(treeA, pending, actor).status, "$actor finalize")
            assertEquals(HttpStatusCode.Forbidden, client.setPhoto(treeA, ana, pending, actor).status, "$actor assign")
        }
        assertError(client.finalize(treeA, pending, Actor.RELATIVE), HttpStatusCode.Forbidden, ownerOnly)
        assertEquals(HttpStatusCode.Forbidden, client.download(treeA, pending, Actor.INVITEE).status, "an invitee can't read")

        val bad = HttpStatusCode.BadRequest
        assertError(client.presign(treeA, purpose = "AVATAR", size = 10), bad, MEDIA_PURPOSE_UNKNOWN_MESSAGE)
        assertError(client.presign(treeA, type = "image/gif", size = 10), bad, MEDIA_CONTENT_TYPE_MESSAGE)
        assertError(client.presign(treeA, type = "image/svg+xml", size = 10), bad, MEDIA_CONTENT_TYPE_MESSAGE)
        assertError(client.presign(treeA, size = 0), bad, MEDIA_BYTE_SIZE_MESSAGE)
        assertError(client.presign(treeA, size = 20L * 1024 * 1024 + 1), bad, MEDIA_BYTE_SIZE_MESSAGE)
        assertError(client.finalize(treeA, "not-a-uuid"), bad, "mediaId must be a UUID")
        assertError(client.download(treeA, "not-a-uuid", Actor.RELATIVE), bad, "mediaId must be a UUID")
        assertError(client.setPhoto(treeA, ana, "not-a-uuid"), bad, "mediaId must be a UUID")
        assertEquals(
            0L,
            count("select count(*) from media where uploaded_by_account_id <> ?", uuid(accounts.getValue(Actor.OWNER).id)) -
                count("select count(*) from media where tree_id = ?", uuid(treeB)),
            "no upload was created by a non-owner of tree A",
        )

        // A client can't supply a key: unknown fields are ignored and the key is generated.
        val withKey = client.send(
            HttpMethod.Post, "${t(treeA)}/media", Actor.OWNER,
            """{"purpose":"PROFILE_PHOTO","contentType":"image/jpeg","byteSize":10,"storageKey":"media/other-tree/x"}""",
        ).decoded<MediaUploadDto>(HttpStatusCode.Created)
        assertEquals("uploads/$treeA/${withKey.media.id}", dataSource.query("select upload_key from media where id = ?", uuid(withKey.media.id)))
    }

    @Test
    fun `media is resolved in the URL's tree only, and its state and purpose are checked before attaching`() = api {
        val ana = client.person(treeA, "Ana")
        val foreign = client.ready(treeB, actor = Actor.OTHER_OWNER)
        val notFound = "No media with id ${foreign.id}"
        assertError(client.finalize(treeA, foreign.id), HttpStatusCode.NotFound, notFound)
        assertError(client.download(treeA, foreign.id, Actor.OWNER), HttpStatusCode.NotFound, notFound)
        assertError(client.setPhoto(treeA, ana, foreign.id), HttpStatusCode.NotFound, notFound)
        val source = client.send(HttpMethod.Post, "${t(treeA)}/sources", Actor.OWNER, encode(CreateSourceRequest("Parish", "CHURCH_RECORD")))
            .decoded<SourceDto>(HttpStatusCode.Created)
        assertError(
            client.send(HttpMethod.Post, "${t(treeA)}/citations", Actor.OWNER, encode(CreateCitationRequest(source.id, "PERSON", ana.id, confidence = 1, mediaId = foreign.id))),
            HttpStatusCode.NotFound, notFound,
        )

        val pending = client.presign(treeA, size = 10).decoded<MediaUploadDto>(HttpStatusCode.Created).media.id
        assertError(client.setPhoto(treeA, ana, pending), HttpStatusCode.Conflict, "Media $pending is not a verified upload; finalize it, or upload the image again")
        val scan = client.ready(treeA, purpose = "CITATION_SCAN")
        assertError(client.setPhoto(treeA, ana, scan.id), HttpStatusCode.BadRequest, MEDIA_PURPOSE_MESSAGE)
        val photo = client.ready(treeA)
        assertError(
            client.send(HttpMethod.Post, "${t(treeA)}/citations", Actor.OWNER, encode(CreateCitationRequest(source.id, "PERSON", ana.id, confidence = 1, mediaId = photo.id))),
            HttpStatusCode.BadRequest, MEDIA_PURPOSE_MESSAGE,
        )

        client.setPhoto(treeA, ana, photo.id).decoded<PersonDto>()
        val bea = client.person(treeA, "Bea")
        assertError(client.setPhoto(treeA, bea, photo.id), HttpStatusCode.Conflict, "Media ${photo.id} is already attached to another record")
        assertError(client.setPhoto(treeA, ana, null, version = ana.version), HttpStatusCode.Conflict, context = "stale person version")
        assertEquals(1L, audits(treeA, "Person", bea.id), "Bea: only her CREATE")
    }

    // --- Who can read what -----------------------------------------------------------------

    @Test
    fun `readers get an image only when they can see the person or citation it belongs to`() = api {
        val ana = client.person(treeA, "Ana")
        val luis = client.person(treeA, "Luis", living = true)
        val anaPhoto = client.ready(treeA)
        val luisPhoto = client.ready(treeA)
        val anaNow = client.setPhoto(treeA, ana, anaPhoto.id).decoded<PersonDto>()
        client.setPhoto(treeA, luis, luisPhoto.id).decoded<PersonDto>()

        assertEquals(HttpStatusCode.OK, client.download(treeA, anaPhoto.id, Actor.RELATIVE).status)
        assertError(client.download(treeA, luisPhoto.id, Actor.RELATIVE), HttpStatusCode.NotFound, "No media with id ${luisPhoto.id}")
        assertNull(client.reload(treeA, luis, Actor.RELATIVE).profileMediaId, "a hidden person's photo ID isn't sent")
        assertEquals(HttpStatusCode.OK, client.download(treeA, luisPhoto.id, Actor.OWNER).status)

        // Replacing a photo leaves the old one stored but unattached: owner only.
        val replacement = client.ready(treeA)
        client.setPhoto(treeA, anaNow, replacement.id).decoded<PersonDto>()
        assertError(client.download(treeA, anaPhoto.id, Actor.RELATIVE), HttpStatusCode.NotFound)
        assertEquals(HttpStatusCode.OK, client.download(treeA, anaPhoto.id, Actor.OWNER).status)

        // Citation scans follow the citation's visibility (FT-002).
        val source = client.send(HttpMethod.Post, "${t(treeA)}/sources", Actor.OWNER, encode(CreateSourceRequest("Census", "CENSUS")))
            .decoded<SourceDto>(HttpStatusCode.Created)
        fun cite(target: PersonDto, scan: MediaDto) = encode(CreateCitationRequest(source.id, "PERSON", target.id, confidence = 2, mediaId = scan.id))
        val openScan = client.ready(treeA, purpose = "CITATION_SCAN")
        val privateScan = client.ready(treeA, purpose = "CITATION_SCAN")
        val cited = client.send(HttpMethod.Post, "${t(treeA)}/citations", Actor.OWNER, cite(ana, openScan)).decoded<CitationDto>(HttpStatusCode.Created)
        assertEquals(openScan.id, cited.mediaId)
        client.send(HttpMethod.Post, "${t(treeA)}/citations", Actor.OWNER, cite(luis, privateScan)).decoded<CitationDto>(HttpStatusCode.Created)
        assertError(
            client.send(HttpMethod.Post, "${t(treeA)}/citations", Actor.OWNER, cite(ana, openScan)),
            HttpStatusCode.Conflict, "Media ${openScan.id} is already attached to another record",
        )
        assertEquals(HttpStatusCode.OK, client.download(treeA, openScan.id, Actor.RELATIVE).status)
        assertError(client.download(treeA, privateScan.id, Actor.RELATIVE), HttpStatusCode.NotFound)

        // An unattached upload is the owner's alone.
        val spare = client.ready(treeA)
        assertError(client.download(treeA, spare.id, Actor.RELATIVE), HttpStatusCode.NotFound)
    }

    // --- FT-007 merge photo choice and lock order --------------------------------------------

    @Test
    fun `a merge keeps the photo the owner chose and never drops one silently`() = api {
        val survivor = client.setPhoto(treeA, client.person(treeA, "Twin"), client.ready(treeA).id).decoded<PersonDto>()
        val retired = client.setPhoto(treeA, client.person(treeA, "Twin"), client.ready(treeA).id).decoded<PersonDto>()
        val path = "${t(treeA)}/people/merge"
        fun request(choices: Map<String, String>) = encode(MergePeopleRequest(survivor.id, survivor.version, retired.id, retired.version, choices))

        assertError(client.send(HttpMethod.Post, path, Actor.OWNER, request(emptyMap())), HttpStatusCode.BadRequest, context = "photo choice missing")
        val merged = client.send(HttpMethod.Post, path, Actor.OWNER, request(mapOf("PROFILE_PHOTO" to "RETIRED"))).decoded<MergePeopleResponse>()
        assertEquals(retired.profileMediaId, merged.survivor.profileMediaId)
        val decision = dataSource.query(
            "select after_json from audit_log where tree_id = ? and entity_type = 'PersonMerge' and entity_id = ?",
            uuid(treeA), "${survivor.id}:${retired.id}",
        )!!
        assertTrue("\"PROFILE_PHOTO\":\"RETIRED\"" in decision, decision)
        // The photo not chosen is still stored, now unattached: the owner can still read it.
        assertEquals(HttpStatusCode.OK, client.download(treeA, survivor.profileMediaId!!, Actor.OWNER).status)
        assertError(client.download(treeA, survivor.profileMediaId!!, Actor.RELATIVE), HttpStatusCode.NotFound)
    }

    @Test
    fun `a photo assignment waits for the graph write lock before any row lock, then sees the committed change`() = api {
        val ana = client.person(treeA, "Ana")
        val photo = client.ready(treeA)
        val key = UUID.fromString(treeA).let { it.mostSignificantBits xor it.leastSignificantBits }
        dataSource.connection.use { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("select pg_advisory_xact_lock($key)") } // as a merge holds it
            coroutineScope {
                val assignment = async { client.setPhoto(treeA, ana, photo.id) }
                awaitBlocked("pg_advisory_xact_lock_shared")
                // Nothing else is locked yet: the media row and the person are free.
                c.createStatement().use { it.execute("select id from media where id = '${photo.id}' for update nowait") }
                c.createStatement().use { it.execute("update person set version = version + 1 where id = '${ana.id}'") }
                c.commit()
                assertError(assignment.await(), HttpStatusCode.Conflict, context = "stale after the lock holder committed")
            }
        }
        assertNull(dataSource.query("select profile_media_id from person where id = ?", uuid(ana.id)))
    }

    @Test
    fun `a merge and a photo assignment on the same person finish without deadlock and agree`() = api {
        val survivor = client.setPhoto(treeA, client.person(treeA, "Pat"), client.ready(treeA).id).decoded<PersonDto>()
        val retired = client.setPhoto(treeA, client.person(treeA, "Pat"), client.ready(treeA).id).decoded<PersonDto>()
        val fresh = client.ready(treeA)
        val mergeBody = encode(MergePeopleRequest(survivor.id, survivor.version, retired.id, retired.version, mapOf("PROFILE_PHOTO" to "RETIRED")))
        val (merge, assign) = withTimeout(30_000) {
            coroutineScope {
                val m = async { client.send(HttpMethod.Post, "${t(treeA)}/people/merge", Actor.OWNER, mergeBody) }
                val a = async { client.setPhoto(treeA, survivor, fresh.id) }
                m.await().status to a.await().status
            }
        }
        assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), setOf(merge, assign), "one wins, the other is stale")
        val stored = dataSource.query("select profile_media_id from person where id = ?", uuid(survivor.id))
        assertEquals(if (merge == HttpStatusCode.OK) retired.profileMediaId else fresh.id, stored)
    }
}
