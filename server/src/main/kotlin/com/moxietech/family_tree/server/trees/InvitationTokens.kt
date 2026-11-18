package com.moxietech.family_tree.server.trees

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * FT-011 invitation secrets. A token is 32 bytes (256 bits) from [SecureRandom],
 * base64url without padding (43 characters). Only its SHA-256 digest is stored, so a
 * database read never yields a usable token; the token itself is returned once to the
 * owner who created the invitation and mailed to the invitee, and never logged or
 * audited.
 *
 * A high-entropy random secret needs no slow hash or salt: guessing a digest is as hard
 * as guessing the token, and the digest is looked up by equality.
 */
internal object InvitationTokens {
    const val TOKEN_BYTES = 32
    private const val TOKEN_LENGTH = 43
    private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{$TOKEN_LENGTH}")
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun digest(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.US_ASCII))

    /** The digest of a well-formed token, or null for anything that can't be one (never matches a row). */
    fun digestOrNull(token: String): ByteArray? = if (TOKEN_PATTERN.matches(token)) digest(token) else null
}
