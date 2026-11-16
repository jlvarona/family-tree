package com.moxietech.family_tree.client

import com.moxietech.family_tree.auth.AccessTokenSource
import com.moxietech.family_tree.auth.TokenResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
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

    private fun api(tokens: AccessTokenSource, vararg replies: Pair<HttpStatusCode, String>): HttpFamilyTreeApi {
        val queue = ArrayDeque(replies.toList())
        val client = HttpClient(
            MockEngine { request ->
                seen += request.url.toString() to request.headers[HttpHeaders.Authorization]
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
}
