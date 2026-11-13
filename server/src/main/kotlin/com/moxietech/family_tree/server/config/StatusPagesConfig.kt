package com.moxietech.family_tree.server.config

import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import io.ktor.http.HttpStatusCode
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
}
