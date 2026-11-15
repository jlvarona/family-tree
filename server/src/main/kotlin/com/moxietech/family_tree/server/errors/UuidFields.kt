package com.moxietech.family_tree.server.errors

import kotlin.uuid.Uuid

/**
 * FT-070: parses a caller-supplied ID from a path parameter or request body
 * field. A malformed value is a structured 400 naming [field]; the raw input
 * and the parser's exception text are never echoed. Any accepted spelling
 * (for example upper-case hex) yields the same [Uuid], so comparisons, lookups
 * and writes made with the result treat equal IDs as equal.
 */
fun parseUuidField(field: String, raw: String): Uuid =
    runCatching { Uuid.parse(raw) }.getOrNull()
        ?: throw ValidationException(listOf("$field must be a UUID"))

fun parseOptionalUuidField(field: String, raw: String?): Uuid? = raw?.let { parseUuidField(field, it) }
