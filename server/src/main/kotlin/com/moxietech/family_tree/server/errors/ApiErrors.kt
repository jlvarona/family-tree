package com.moxietech.family_tree.server.errors

class ValidationException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

class NotFoundException(message: String) : RuntimeException(message)

/** The caller is authenticated but has no grant covering the requested tree/branch. */
class ForbiddenException(message: String) : RuntimeException(message)

/** The request conflicts with existing state, such as a link that already exists (FT-070). */
class ConflictException(message: String) : RuntimeException(message)

/**
 * FT-001: an update named a version that is no longer the stored one, because
 * someone else changed the record first (or the caller sent a version it never
 * read). Thrown inside the update's transaction, so the record and the audit log
 * are left untouched; maps to 409.
 */
fun staleVersion(entity: String, id: String): ConflictException =
    ConflictException("$entity $id has been changed since you read it; reload it and try again")

/**
 * FT-001: a delete named a record that another record in the same tree still
 * references (a foreign key or a citation). Nothing is written; maps to 409.
 * The message doesn't say what refers to it.
 */
fun referenced(entity: String, id: String): ConflictException =
    ConflictException("$entity $id is still referenced by other records; remove those references first")

/**
 * FT-001 delete contract: `?version=N` is required, given once, and N is a
 * positive decimal integer without sign or leading zeros that fits a 64-bit
 * signed long (1..9223372036854775807). Anything else is a 400 with this message.
 */
const val VERSION_PARAM_MESSAGE = "version query parameter must be a single positive integer"

private val POSITIVE_INTEGER = Regex("[1-9][0-9]{0,18}")

fun parseVersionParam(values: List<String>?): Long =
    values?.singleOrNull()?.takeIf { POSITIVE_INTEGER.matches(it) }?.toLongOrNull()
        ?: throw ValidationException(listOf(VERSION_PARAM_MESSAGE))

fun List<String>.requireValid() {
    if (isNotEmpty()) throw ValidationException(this)
}
