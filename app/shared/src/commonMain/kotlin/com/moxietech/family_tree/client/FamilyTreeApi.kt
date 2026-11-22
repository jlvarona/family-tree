package com.moxietech.family_tree.client

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.ApproveProposalRequest
import com.moxietech.family_tree.api.ChangeProposalDto
import com.moxietech.family_tree.api.DecideProposalRequest
import com.moxietech.family_tree.api.TreeCapabilitiesDto
import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.api.CreateMediaUploadRequest
import com.moxietech.family_tree.api.CreateTreeRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.MediaDownloadDto
import com.moxietech.family_tree.api.MediaDto
import com.moxietech.family_tree.api.MediaUploadDto
import com.moxietech.family_tree.api.SetProfilePhotoRequest
import com.moxietech.family_tree.api.MergePeopleRequest
import com.moxietech.family_tree.api.MergePeopleResponse
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.RedeemInvitationRequest
import com.moxietech.family_tree.api.RedeemInvitationResponse
import com.moxietech.family_tree.api.SourceDto
import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.auth.AccessTokenSource
import com.moxietech.family_tree.domain.CitationTarget
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
 * FT-014: what a graph write did. Writes outside the caller's branches can be queued as a
 * change proposal for the tree owner or a branch owner (`202 Accepted`) instead of applied.
 */
sealed interface WriteResult<out T> {
    data class Applied<T>(val value: T) : WriteResult<T>

    data class Queued(val proposal: ChangeProposalDto) : WriteResult<Nothing>
}

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

    /**
     * FT-014: [WriteResult.Queued] when the server queued the union as a change proposal for
     * review (`202`) instead of creating it (a partner outside the caller's branches).
     */
    suspend fun createUnion(treeId: String, request: CreateUnionRequest): WriteResult<UnionDto>

    /** FT-014: [WriteResult.Queued] when the link was queued for review (`202`) instead of made. */
    suspend fun addChild(treeId: String, unionId: String, request: AddUnionChildRequest): WriteResult<UnionDto>

    /**
     * Merges two records of one person (FT-007, owner only). A `409` means either
     * person changed since the versions in [request] were read, or the merge would
     * break the graph; the caller reloads both and asks again.
     */
    suspend fun mergePeople(treeId: String, request: MergePeopleRequest): MergePeopleResponse

    /** FT-008: a pending upload and its presigned `PUT` (owner only). */
    suspend fun createMediaUpload(treeId: String, request: CreateMediaUploadRequest): MediaUploadDto

    /**
     * FT-008: asks the server to verify an upload. Safe to repeat: a `READY` upload is
     * returned unchanged; `409` means not uploaded yet (or rejected earlier); `400` means
     * the file isn't a valid image of the declared type and size.
     */
    suspend fun finalizeMedia(treeId: String, mediaId: String): MediaDto

    /** FT-008: a short-lived read URL for an image the caller may see (`404` otherwise). */
    suspend fun mediaDownload(treeId: String, mediaId: String): MediaDownloadDto

    /** FT-008: sets or removes (null) a person's profile photo at the person's [version]. */
    suspend fun setProfilePhoto(treeId: String, personId: String, mediaId: String?, version: Long): PersonDto

    /** The tree's sources (`GET …/sources`, any active member): the metadata citations point at. */
    suspend fun sources(treeId: String): List<SourceDto>

    /**
     * FT-002: the citations on one person, event or union
     * (`GET …/{people|events|unions}/{id}/citations`). The server returns an empty list
     * for a person or union with a hidden subject, and `404` for an event it hides.
     */
    suspend fun citations(treeId: String, target: CitationTarget): List<CitationDto>

    /**
     * FT-011: accepts an invitation to [treeId] (`POST …/trees/{treeId}/invitations/redeem`)
     * with [token] in the body only. `404`: not a valid invitation (unknown, expired,
     * revoked, already used, another tree's or invitations are off); `409`: this account
     * can't accept it (already a member, the person is taken, or an email invitation for an
     * address this account hasn't verified).
     */
    suspend fun redeemInvitation(treeId: String, token: String): RedeemInvitationResponse

    /** FT-014: what the caller may do in [treeId] (`GET …/capabilities`), for showing controls. */
    suspend fun capabilities(treeId: String): TreeCapabilitiesDto

    /** FT-014: the proposals the caller made or may review, already shaped by the server (`404` while sharing is off). */
    suspend fun proposals(treeId: String): List<ChangeProposalDto>

    /** FT-014: approves [request]'s fields at the proposal's version; `409` when it or its record changed. */
    suspend fun approveProposal(treeId: String, proposalId: String, request: ApproveProposalRequest): ChangeProposalDto

    suspend fun rejectProposal(treeId: String, proposalId: String, version: Long): ChangeProposalDto

    suspend fun withdrawProposal(treeId: String, proposalId: String, version: Long): ChangeProposalDto
}

/** The API's `targetType` for a `:core` citation target (FT-002). */
val CitationTarget.apiType: String
    get() = when (this) {
        is CitationTarget.OnPerson -> "PERSON"
        is CitationTarget.OnEvent -> "EVENT"
        is CitationTarget.OnUnion -> "UNION"
    }

/** The ID of the cited person, event or union. */
val CitationTarget.targetId: String
    get() = when (this) {
        is CitationTarget.OnPerson -> personId.value
        is CitationTarget.OnEvent -> eventId.value
        is CitationTarget.OnUnion -> unionId.value
    }

private val CitationTarget.collection: String
    get() = when (this) {
        is CitationTarget.OnPerson -> "people"
        is CitationTarget.OnEvent -> "events"
        is CitationTarget.OnUnion -> "unions"
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

    override suspend fun createUnion(treeId: String, request: CreateUnionRequest): WriteResult<UnionDto> = decodeWrite(
        send(HttpMethod.Post, "/trees/${seg(treeId)}/unions", json.encodeToString(CreateUnionRequest.serializer(), request)),
        UnionDto.serializer(),
    )

    override suspend fun addChild(treeId: String, unionId: String, request: AddUnionChildRequest): WriteResult<UnionDto> = decodeWrite(
        send(
            HttpMethod.Post,
            "/trees/${seg(treeId)}/unions/${seg(unionId)}/children",
            json.encodeToString(AddUnionChildRequest.serializer(), request),
        ),
        UnionDto.serializer(),
    )

    /** FT-014: `202 Accepted` carries the queued proposal; any other success the applied record. */
    private suspend fun <T> decodeWrite(response: HttpResponse, result: DeserializationStrategy<T>): WriteResult<T> =
        if (response.status == HttpStatusCode.Accepted) {
            WriteResult.Queued(decode(response, ChangeProposalDto.serializer()))
        } else {
            WriteResult.Applied(decode(response, result))
        }

    override suspend fun mergePeople(treeId: String, request: MergePeopleRequest): MergePeopleResponse = post(
        "/trees/${seg(treeId)}/people/merge",
        MergePeopleRequest.serializer(),
        request,
        MergePeopleResponse.serializer(),
    )

    override suspend fun createMediaUpload(treeId: String, request: CreateMediaUploadRequest): MediaUploadDto =
        post("/trees/${seg(treeId)}/media", CreateMediaUploadRequest.serializer(), request, MediaUploadDto.serializer())

    override suspend fun finalizeMedia(treeId: String, mediaId: String): MediaDto =
        decode(send(HttpMethod.Post, "/trees/${seg(treeId)}/media/${seg(mediaId)}/finalize", body = null), MediaDto.serializer())

    override suspend fun mediaDownload(treeId: String, mediaId: String): MediaDownloadDto =
        get("/trees/${seg(treeId)}/media/${seg(mediaId)}/download", MediaDownloadDto.serializer())

    override suspend fun setProfilePhoto(treeId: String, personId: String, mediaId: String?, version: Long): PersonDto = decode(
        send(
            HttpMethod.Put,
            "/trees/${seg(treeId)}/people/${seg(personId)}/profile-photo",
            json.encodeToString(SetProfilePhotoRequest.serializer(), SetProfilePhotoRequest(mediaId, version)),
        ),
        PersonDto.serializer(),
    )

    override suspend fun sources(treeId: String): List<SourceDto> =
        get("/trees/${seg(treeId)}/sources", ListSerializer(SourceDto.serializer()))

    override suspend fun citations(treeId: String, target: CitationTarget): List<CitationDto> = get(
        "/trees/${seg(treeId)}/${target.collection}/${seg(target.targetId)}/citations",
        ListSerializer(CitationDto.serializer()),
    )

    override suspend fun redeemInvitation(treeId: String, token: String): RedeemInvitationResponse = post(
        "/trees/${seg(treeId)}/invitations/redeem",
        RedeemInvitationRequest.serializer(),
        RedeemInvitationRequest(token),
        RedeemInvitationResponse.serializer(),
    )

    override suspend fun capabilities(treeId: String): TreeCapabilitiesDto =
        get("/trees/${seg(treeId)}/capabilities", TreeCapabilitiesDto.serializer())

    override suspend fun proposals(treeId: String): List<ChangeProposalDto> =
        get("/trees/${seg(treeId)}/proposals", ListSerializer(ChangeProposalDto.serializer()))

    override suspend fun approveProposal(treeId: String, proposalId: String, request: ApproveProposalRequest): ChangeProposalDto = post(
        "/trees/${seg(treeId)}/proposals/${seg(proposalId)}/approve",
        ApproveProposalRequest.serializer(),
        request,
        ChangeProposalDto.serializer(),
    )

    override suspend fun rejectProposal(treeId: String, proposalId: String, version: Long): ChangeProposalDto = post(
        "/trees/${seg(treeId)}/proposals/${seg(proposalId)}/reject",
        DecideProposalRequest.serializer(),
        DecideProposalRequest(version),
        ChangeProposalDto.serializer(),
    )

    override suspend fun withdrawProposal(treeId: String, proposalId: String, version: Long): ChangeProposalDto = post(
        "/trees/${seg(treeId)}/proposals/${seg(proposalId)}/withdraw",
        DecideProposalRequest.serializer(),
        DecideProposalRequest(version),
        ChangeProposalDto.serializer(),
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
