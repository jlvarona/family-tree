package com.moxietech.family_tree.client

import com.moxietech.family_tree.api.CreateMediaUploadRequest
import com.moxietech.family_tree.api.MergePeopleRequest
import com.moxietech.family_tree.api.PresignedUploadDto
import com.moxietech.family_tree.api.SetProfilePhotoRequest
import com.moxietech.family_tree.auth.AccessTokenSource
import com.moxietech.family_tree.auth.TokenResult
import com.moxietech.family_tree.domain.CitationTarget
import com.moxietech.family_tree.domain.EventId
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.UnionId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HttpFamilyTreeApiTest {

    private class Tokens(var current: String = "token-1", var refreshTo: TokenResult = TokenResult.Token("token-2", 1)) :
        AccessTokenSource {
        var ended = false
        val rejected = mutableListOf<String>()

        override suspend fun accessToken(): TokenResult = TokenResult.Token(current, 1)

        override suspend fun onRejected(rejected: TokenResult.Token): TokenResult {
            this.rejected += rejected.value
            return refreshTo.also { if (it is TokenResult.Token) current = it.value }
        }

        override fun endSession(rejected: TokenResult.Token) {
            ended = true
        }
    }

    private val seen = mutableListOf<Pair<String, String?>>()
    private val sent = mutableListOf<Pair<HttpMethod, String?>>()

    private fun api(tokens: AccessTokenSource, vararg replies: Pair<HttpStatusCode, String>): HttpFamilyTreeApi {
        val queue = ArrayDeque(replies.toList())
        val client = HttpClient(
            MockEngine { request ->
                seen += request.url.toString() to request.headers[HttpHeaders.Authorization]
                sent += request.method to (request.body as? TextContent)?.text
                val (status, body) = queue.removeFirst()
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        )
        return HttpFamilyTreeApi(client, "http://localhost:3000", tokens)
    }

    @Test
    fun sendsTheBearerTokenToTheSameOriginApi() = runTest {
        val api = api(Tokens(), HttpStatusCode.OK to """[{"id":"t1","name":"Tree","ownerAccountId":"a","version":0}]""")

        val trees = api.trees()

        assertEquals("t1", trees.single().id)
        assertEquals("http://localhost:3000/api/v1/trees" to "Bearer token-1", seen.single())
    }

    @Test
    fun unauthorizedRetriesOnceWithARefreshedToken() = runTest {
        val tokens = Tokens()
        val api = api(
            tokens,
            HttpStatusCode.Unauthorized to "",
            HttpStatusCode.OK to """{"id":"a","email":"dev@example.com","displayName":"Dev","locale":"en"}""",
        )

        assertEquals("a", api.me().id)
        assertEquals(listOf("token-1"), tokens.rejected)
        assertEquals(listOf("Bearer token-1", "Bearer token-2"), seen.map { it.second })
    }

    @Test
    fun secondUnauthorizedEndsTheSession() = runTest {
        val tokens = Tokens()
        val api = api(tokens, HttpStatusCode.Unauthorized to "", HttpStatusCode.Unauthorized to "")

        val error = assertFailsWith<ApiException> { api.me() }
        assertEquals(ApiFailure.SESSION_EXPIRED, error.failure)
        assertTrue(tokens.ended)
    }

    @Test
    fun failedRefreshReportsAnExpiredSessionWithoutRetrying() = runTest {
        val tokens = Tokens(refreshTo = TokenResult.Expired)
        val api = api(tokens, HttpStatusCode.Unauthorized to "")

        assertEquals(ApiFailure.SESSION_EXPIRED, assertFailsWith<ApiException> { api.me() }.failure)
        assertEquals(1, seen.size)
    }

    @Test
    fun errorStatusesMapToFailureKindsWithoutServerText() = runTest {
        val cases = listOf(
            HttpStatusCode.BadRequest to ApiFailure.INVALID,
            HttpStatusCode.Forbidden to ApiFailure.FORBIDDEN,
            HttpStatusCode.NotFound to ApiFailure.NOT_FOUND,
            HttpStatusCode.Conflict to ApiFailure.CONFLICT,
            HttpStatusCode.InternalServerError to ApiFailure.SERVER,
            HttpStatusCode.ServiceUnavailable to ApiFailure.UNAVAILABLE,
        )
        for ((status, failure) in cases) {
            val api = api(Tokens(), status to """{"message":"Person 123 secret"}""")
            val error = assertFailsWith<ApiException> { api.graph("t1") }
            assertEquals(failure, error.failure)
            assertTrue("secret" !in (error.message ?: ""))
        }
    }

    @Test
    fun pathIdsFromTheUrlAreEncoded() = runTest {
        val api = api(Tokens(), HttpStatusCode.OK to "[]")

        api.ancestors("t1", "../me?x")

        assertEquals("http://localhost:3000/api/v1/trees/t1/people/..%2Fme%3Fx/ancestors", seen.single().first)
    }

    @Test
    fun personEventsAreReadFromTheTimelineRoute() = runTest {
        val api = api(
            Tokens(),
            HttpStatusCode.OK to """[{"id":"e1","treeId":"t1","personId":"p/1","unionId":null,"type":"BIRTH",
                "date":{"displayText":"abt 1890","earliestIso":"1889-01-01","latestIso":"1891-12-31"},
                "place":{"id":"pl","name":"Toledo","hierarchy":null,"latitude":null,"longitude":null},
                "notes":null,"version":0,"futureField":true}]""",
        )

        val events = api.events("t1", "p/1")

        assertEquals("http://localhost:3000/api/v1/trees/t1/people/p%2F1/events", seen.single().first)
        assertEquals("abt 1890", events.single().date?.displayText)
        assertEquals("Toledo", events.single().place?.name)
    }

    @Test
    fun sourcesAndCitationsAreReadFromTheirRoutesWithEncodedIds() = runTest {
        val api = api(
            Tokens(),
            HttpStatusCode.OK to """[{"id":"s1","treeId":"t1","title":"Parish book","type":"CHURCH_RECORD","author":null,
                "publisher":null,"repository":null,"url":"https://example.org/x","date":null,"version":1,"futureField":0}]""",
            HttpStatusCode.OK to """[{"id":"c1","sourceId":"s1","targetType":"EVENT","targetId":"e/1","page":"p. 4",
                "confidence":3,"note":null,"mediaId":"m1","futureField":"x"}]""",
            HttpStatusCode.OK to "[]",
            HttpStatusCode.OK to "[]",
        )

        assertEquals("Parish book", api.sources("t1").single().title)
        val citation = api.citations("t1", CitationTarget.OnEvent(EventId("e/1"))).single()
        api.citations("t1", CitationTarget.OnPerson(PersonId("p?1")))
        api.citations("t1", CitationTarget.OnUnion(UnionId("u1")))

        assertEquals("m1", citation.mediaId)
        assertEquals(
            listOf(
                "http://localhost:3000/api/v1/trees/t1/sources",
                "http://localhost:3000/api/v1/trees/t1/events/e%2F1/citations",
                "http://localhost:3000/api/v1/trees/t1/people/p%3F1/citations",
                "http://localhost:3000/api/v1/trees/t1/unions/u1/citations",
            ),
            seen.map { it.first },
        )
        assertTrue(sent.all { it.first == HttpMethod.Get && it.second == null })
    }

    @Test
    fun mergePostsTheWholeDecisionAndReadsTheSurvivor() = runTest {
        val api = api(
            Tokens(),
            HttpStatusCode.OK to """{"survivor":{"id":"p1","treeId":"t/1","givenNames":"Ana","surnamePaternal":null,
                "surnameMaternal":null,"fullName":"Ana","sex":"FEMALE","isLiving":false,"profileMediaId":null,"bio":null,
                "version":4,"futureField":1},"retiredId":"p2"}""",
        )
        val request = MergePeopleRequest("p1", 3, "p2", 7, mapOf("IS_LIVING" to "RETIRED"))

        val result = api.mergePeople("t/1", request)

        assertEquals("http://localhost:3000/api/v1/trees/t%2F1/people/merge", seen.single().first)
        val (method, body) = sent.single()
        assertEquals(HttpMethod.Post, method)
        assertEquals(request, Json.decodeFromString<MergePeopleRequest>(body!!))
        assertEquals(4, result.survivor.version)
        assertEquals("p2", result.retiredId)

        val conflict = api(Tokens(), HttpStatusCode.Conflict to """{"message":"Person p1 has been changed"}""")
        assertEquals(ApiFailure.CONFLICT, assertFailsWith<ApiException> { conflict.mergePeople("t1", request) }.failure)
    }

    @Test
    fun photoRoutesUseTheApiWithTheTokenAndNeverSendImageBytes() = runTest {
        val media = """{"id":"m/1","treeId":"t1","purpose":"PROFILE_PHOTO","status":"READY","contentType":"image/png",
            "byteSize":10,"width":2,"height":3,"version":2}"""
        val api = api(
            Tokens(),
            HttpStatusCode.Created to """{"media":$media,"upload":{"url":"https://store.test/u","method":"PUT",
                "headers":{"content-type":"image/png"},"expiresAt":"2026-11-16T10:10:00Z"}}""",
            HttpStatusCode.OK to media,
            HttpStatusCode.OK to """{"mediaId":"m/1","url":"https://store.test/g","contentType":"image/png","expiresAt":"x"}""",
            HttpStatusCode.OK to """{"id":"p1","treeId":"t1","givenNames":"Ana","surnamePaternal":null,"surnameMaternal":null,
                "fullName":"Ana","sex":"FEMALE","isLiving":false,"profileMediaId":"m/1","bio":null,"version":5}""",
        )

        val upload = api.createMediaUpload("t1", CreateMediaUploadRequest("PROFILE_PHOTO", "image/png", 10))
        assertEquals("https://store.test/u", upload.upload.url)
        assertEquals("READY", api.finalizeMedia("t1", "m/1").status)
        assertEquals("https://store.test/g", api.mediaDownload("t1", "m/1").url)
        assertEquals("m/1", api.setProfilePhoto("t1", "p1", "m/1", 4).profileMediaId)

        assertEquals(
            listOf(
                "http://localhost:3000/api/v1/trees/t1/media",
                "http://localhost:3000/api/v1/trees/t1/media/m%2F1/finalize",
                "http://localhost:3000/api/v1/trees/t1/media/m%2F1/download",
                "http://localhost:3000/api/v1/trees/t1/people/p1/profile-photo",
            ),
            seen.map { it.first },
        )
        assertTrue(seen.all { it.second == "Bearer token-1" })
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Post, HttpMethod.Get, HttpMethod.Put), sent.map { it.first })
        assertEquals(SetProfilePhotoRequest("m/1", 4), Json.decodeFromString<SetProfilePhotoRequest>(sent.last().second!!))
    }

    @Test
    fun presignedTransfersCarryOnlyTheSignedHeadersAndNoToken() = runTest {
        val requests = mutableListOf<Triple<HttpMethod, String, Map<String, List<String>>>>()
        val replies = ArrayDeque(listOf(HttpStatusCode.OK, HttpStatusCode.OK, HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable))
        val transfer = HttpMediaTransfer(
            HttpClient(
                MockEngine { request ->
                    requests += Triple(request.method, request.url.toString(), request.headers.entries().associate { it.key to it.value })
                    respond(byteArrayOf(9, 8, 7), replies.removeFirst(), headersOf(HttpHeaders.ContentType, "image/png"))
                },
            ),
        )
        val upload = PresignedUploadDto("https://store.test/u?X-Amz-Signature=s", "PUT", mapOf("content-type" to "image/png"), "x")

        transfer.upload(upload, byteArrayOf(1, 2, 3))
        assertTrue(transfer.download("https://store.test/g").contentEquals(byteArrayOf(9, 8, 7)))
        assertEquals(ApiFailure.FORBIDDEN, assertFailsWith<ApiException> { transfer.upload(upload, byteArrayOf(1)) }.failure)
        assertEquals(ApiFailure.UNAVAILABLE, assertFailsWith<ApiException> { transfer.download("https://store.test/g") }.failure)
        assertEquals(ApiFailure.SERVER, assertFailsWith<ApiException> { transfer.download("javascript:alert(1)") }.failure)

        assertEquals(HttpMethod.Put to "https://store.test/u?X-Amz-Signature=s", requests.first().first to requests.first().second)
        assertTrue(requests.none { (_, _, headers) -> headers.keys.any { it.equals(HttpHeaders.Authorization, ignoreCase = true) } })
    }
}
