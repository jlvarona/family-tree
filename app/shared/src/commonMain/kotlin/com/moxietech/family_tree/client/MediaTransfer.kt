package com.moxietech.family_tree.client

import com.moxietech.family_tree.api.PresignedUploadDto
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

/**
 * FT-008: moves image bytes directly between the browser and the object store over
 * presigned URLs, never through the API. It carries no session token: the signature
 * in the URL is the only authorization. Failures are [ApiException]s: a refused or
 * expired signature is [ApiFailure.FORBIDDEN], anything unreachable (including a
 * missing CORS rule, which the browser reports as a network error) is
 * [ApiFailure.UNAVAILABLE].
 */
interface MediaTransfer {
    suspend fun upload(upload: PresignedUploadDto, bytes: ByteArray)

    suspend fun download(url: String): ByteArray
}

/** For platforms without photo support yet: every transfer is unavailable. */
object NoMediaTransfer : MediaTransfer {
    override suspend fun upload(upload: PresignedUploadDto, bytes: ByteArray) = throw ApiException(ApiFailure.UNAVAILABLE)

    override suspend fun download(url: String): ByteArray = throw ApiException(ApiFailure.UNAVAILABLE)
}

class HttpMediaTransfer(private val http: HttpClient) : MediaTransfer {

    override suspend fun upload(upload: PresignedUploadDto, bytes: ByteArray) {
        if (!upload.method.equals("PUT", ignoreCase = true)) throw ApiException(ApiFailure.SERVER)
        val target = checkedUrl(upload.url)
        val contentType = upload.headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }?.value
            ?: throw ApiException(ApiFailure.SERVER)
        val response = call {
            http.request {
                method = HttpMethod.Put
                this.url(target)
                // Exactly the signed headers; Content-Type travels with the body.
                upload.headers.filterKeys { !it.equals("content-type", ignoreCase = true) }.forEach { (name, value) -> header(name, value) }
                setBody(ByteArrayContent(bytes, ContentType.parse(contentType)))
            }
        }
        check(response)
    }

    override suspend fun download(url: String): ByteArray {
        val target = checkedUrl(url)
        val response = call {
            http.request {
                method = HttpMethod.Get
                this.url(target)
            }
        }
        check(response)
        return try {
            response.bodyAsBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(ApiFailure.UNAVAILABLE)
        }
    }

    private fun checkedUrl(url: String): String =
        url.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: throw ApiException(ApiFailure.SERVER)

    private fun check(response: HttpResponse) {
        if (response.status.isSuccess()) return
        throw ApiException(
            when (response.status) {
                HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized -> ApiFailure.FORBIDDEN
                HttpStatusCode.NotFound -> ApiFailure.NOT_FOUND
                else -> ApiFailure.UNAVAILABLE
            },
        )
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The cause isn't kept: it would include the signed URL.
            throw ApiException(ApiFailure.UNAVAILABLE)
        }
}
