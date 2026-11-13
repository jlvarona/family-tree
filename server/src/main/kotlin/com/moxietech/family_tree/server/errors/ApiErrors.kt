package com.moxietech.family_tree.server.errors

class ValidationException(val errors: List<String>) : RuntimeException(errors.joinToString("; "))

class NotFoundException(message: String) : RuntimeException(message)

/** The caller is authenticated but has no grant covering the requested tree/branch. */
class ForbiddenException(message: String) : RuntimeException(message)

fun List<String>.requireValid() {
    if (isNotEmpty()) throw ValidationException(this)
}
