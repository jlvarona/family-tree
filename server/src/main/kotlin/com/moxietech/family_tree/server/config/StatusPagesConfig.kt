package com.moxietech.family_tree.server.config

import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.media.ObjectStoreUnavailableException
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorResponse(val message: String, val fieldErrors: List<String> = emptyList())

fun StatusPagesConfig.configureErrorMapping() {
    exception<ValidationException> { call, cause ->
        call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(cause.message ?: "Invalid request", cause.errors))
    }
    exception<NotFoundException> { call, cause ->
        call.respond(HttpStatusCode.NotFound, ApiErrorResponse(cause.message ?: "Not found"))
    }
    exception<ForbiddenException> { call, cause ->
        call.respond(HttpStatusCode.Forbidden, ApiErrorResponse(cause.message ?: "Forbidden"))
    }
    exception<ConflictException> { call, cause ->
        call.respond(HttpStatusCode.Conflict, ApiErrorResponse(cause.message ?: "Conflict"))
    }
    // FT-008: the object store failed or refused; the request can be retried. The cause
    // (bucket, endpoint) is not sent.
    exception<ObjectStoreUnavailableException> { call, _ ->
        call.respond(HttpStatusCode.ServiceUnavailable, ApiErrorResponse(STORAGE_UNAVAILABLE_MESSAGE))
    }
    // Ktor raises BadRequestException when a body can't be decoded: malformed JSON,
    // a missing required field or a wrong JSON type (FT-070). The cause's text can
    // quote the body, so respond with a fixed message. An unsupported content type
    // is a different exception and keeps Ktor's 415.
    exception<BadRequestException> { call, _ ->
        call.respond(HttpStatusCode.BadRequest, ApiErrorResponse(MALFORMED_BODY_MESSAGE))
    }
}

const val MALFORMED_BODY_MESSAGE = "Malformed request body"

const val STORAGE_UNAVAILABLE_MESSAGE = "Photo storage is unavailable; try again"
