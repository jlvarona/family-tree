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

fun List<String>.requireValid() {
    if (isNotEmpty()) throw ValidationException(this)
}
