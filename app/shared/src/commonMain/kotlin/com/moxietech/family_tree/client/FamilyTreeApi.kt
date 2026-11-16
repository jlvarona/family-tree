package com.moxietech.family_tree.client

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.api.CreateTreeRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.auth.AccessTokenSource
import com.moxietech.family_tree.auth.TokenResult
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Why an API call failed, in terms the UI can show in either language. */
enum class ApiFailure {
    /** The session ended; the UI returns to the sign-in screen. */
    SESSION_EXPIRED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    INVALID,

    /** The API or Keycloak couldn't be reached. */
    UNAVAILABLE,
    SERVER,
}

/**
 * Carries only the failure kind. Server error text is English and may name
 * IDs, so it isn't shown or kept.
 */
class ApiException(val failure: ApiFailure) : Exception("API call failed: $failure")

/**
 * The existing `/api/v1` create and read endpoints the web workflow uses, with
 * `:core` DTOs. Failures are thrown as [ApiException].
 */
interface FamilyTreeApi {
    suspend fun me(): AccountDto

    suspend fun trees(): List<TreeDto>

    suspend fun createTree(name: String): TreeDto

    suspend fun tree(treeId: String): TreeDto

    /** The whole-tree bundle (FT-004). */
    suspend fun graph(treeId: String): TreeGraphDto

    suspend fun person(treeId: String, personId: String): PersonDto

    suspend fun ancestors(treeId: String, personId: String): List<PersonDto>

    suspend fun descendants(treeId: String, personId: String): List<PersonDto>

    /**
     * The person's events (`GET …/people/{personId}/events`). The server returns
     * an empty list for a living person hidden from the caller (FT-066).
     */
    suspend fun events(treeId: String, personId: String): List<EventDto>

    suspend fun createPerson(treeId: String, request: CreatePersonRequest): CreatePersonResponse

    suspend fun createUnion(treeId: String, request: CreateUnionRequest): UnionDto

    suspend fun addChild(treeId: String, unionId: String, request: AddUnionChildRequest): UnionDto
}

/**
 * [FamilyTreeApi] over HTTP. Every call carries the session's access token
 * and, on a `401`, retries once with a refreshed token; a second `401` ends the
 * session.
 */
class HttpFamilyTreeApi(
    private val http: HttpClient,
    baseUrl: String,
    private val tokens: AccessTokenSource,
) : FamilyTreeApi {
    private val api = baseUrl.trimEnd('/') + "/api/v1"

    override suspend fun me(): AccountDto = get("/me", AccountDto.serializer())

    override suspend fun trees(): List<TreeDto> = get("/trees", ListSerializer(TreeDto.serializer()))

    override suspend fun createTree(name: String): TreeDto =
        post("/trees", CreateTreeRequest.serializer(), CreateTreeRequest(name), TreeDto.serializer())

    override suspend fun tree(treeId: String): TreeDto = get("/trees/${seg(treeId)}", TreeDto.serializer())

    override suspend fun graph(treeId: String): TreeGraphDto =
        get("/trees/${seg(treeId)}/graph", TreeGraphDto.serializer())

    override suspend fun person(treeId: String, personId: String): PersonDto =
        get("/trees/${seg(treeId)}/people/${seg(personId)}", PersonDto.serializer())

    override suspend fun ancestors(treeId: String, personId: String): List<PersonDto> =
        get("/trees/${seg(treeId)}/people/${seg(personId)}/ancestors", ListSerializer(PersonDto.serializer()))

    override suspend fun descendants(treeId: String, personId: String): List<PersonDto> =
        get("/trees/${seg(treeId)}/people/${seg(personId)}/descendants", ListSerializer(PersonDto.serializer()))

    override suspend fun events(treeId: String, personId: String): List<EventDto> =
        get("/trees/${seg(treeId)}/people/${seg(personId)}/events", ListSerializer(EventDto.serializer()))

    override suspend fun createPerson(treeId: String, request: CreatePersonRequest): CreatePersonResponse =
        post("/trees/${seg(treeId)}/people", CreatePersonRequest.serializer(), request, CreatePersonResponse.serializer())

    override suspend fun createUnion(treeId: String, request: CreateUnionRequest): UnionDto =
        post("/trees/${seg(treeId)}/unions", CreateUnionRequest.serializer(), request, UnionDto.serializer())

    override suspend fun addChild(treeId: String, unionId: String, request: AddUnionChildRequest): UnionDto = post(
        "/trees/${seg(treeId)}/unions/${seg(unionId)}/children",
        AddUnionChildRequest.serializer(),
        request,
        UnionDto.serializer(),
    )

    private suspend fun <T> get(path: String, result: DeserializationStrategy<T>): T =
        decode(send(HttpMethod.Get, path, body = null), result)

    private suspend fun <B, T> post(
        path: String,
        bodySerializer: SerializationStrategy<B>,
        body: B,
        result: DeserializationStrategy<T>,
    ): T = decode(send(HttpMethod.Post, path, json.encodeToString(bodySerializer, body)), result)

    private suspend fun <T> decode(response: HttpResponse, result: DeserializationStrategy<T>): T =
        try {
            json.decodeFromString(result, response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(ApiFailure.SERVER)
        }

    private suspend fun send(method: HttpMethod, path: String, body: String?): HttpResponse {
        var token = tokenOrThrow(tokens.accessToken())
        var retried = false
        while (true) {
            val response = execute(method, path, body, token.value)
            if (response.status == HttpStatusCode.Unauthorized) {
                if (retried) {
                    tokens.endSession(token)
                    throw ApiException(ApiFailure.SESSION_EXPIRED)
                }
                retried = true
                token = tokenOrThrow(tokens.onRejected(token))
                continue
            }
            if (response.status.isSuccess()) return response
            throw ApiException(failureFor(response.status))
        }
    }

    private suspend fun execute(method: HttpMethod, path: String, body: String?, token: String): HttpResponse =
        try {
            http.request {
                this.method = method
                url(api + path)
                bearerAuth(token)
                if (body != null) setBody(TextContent(body, ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The cause isn't kept: it may include the URL or headers.
            throw ApiException(ApiFailure.UNAVAILABLE)
        }

    private fun tokenOrThrow(result: TokenResult): TokenResult.Token = when (result) {
        is TokenResult.Token -> result
        TokenResult.Expired -> throw ApiException(ApiFailure.SESSION_EXPIRED)
        TokenResult.Unavailable -> throw ApiException(ApiFailure.UNAVAILABLE)
    }

    /** Percent-encodes an ID for one path segment, including `/` and `?`. */
    private fun seg(value: String): String = value.encodeURLParameter()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        internal fun failureFor(status: HttpStatusCode): ApiFailure = when (status.value) {
            400 -> ApiFailure.INVALID
            401 -> ApiFailure.SESSION_EXPIRED
            403 -> ApiFailure.FORBIDDEN
            404 -> ApiFailure.NOT_FOUND
            409 -> ApiFailure.CONFLICT
            502, 503, 504 -> ApiFailure.UNAVAILABLE
            else -> ApiFailure.SERVER
        }
    }
}
