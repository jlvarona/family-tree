package com.moxietech.family_tree.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** A token endpoint response (RFC 6749 §5.1, OIDC Core §3.1.3.3). */
@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("refresh_expires_in") val refreshExpiresIn: Long? = null,
    @SerialName("id_token") val idToken: String? = null,
) {
    /**
     * A non-blank Bearer access token with a positive lifetime, and no blank
     * optional tokens. Anything else is refused rather than installed.
     */
    fun isUsable(): Boolean =
        accessToken.isNotBlank() &&
            tokenType.equals("Bearer", ignoreCase = true) &&
            expiresIn > 0 &&
            refreshToken?.isNotBlank() != false &&
            idToken?.isNotBlank() != false
}

/** The ID-token claims the client checks. */
internal data class IdTokenClaims(
    val issuer: String,
    val subject: String,
    val audience: List<String>,
    val authorizedParty: String?,
    val expiresAtSeconds: Long,
    val nonce: String?,
)

/**
 * ID-token checks from OIDC Core §3.1.3.7 (code flow) and §12.2 (refresh).
 *
 * The token comes straight from the configured HTTPS token endpoint, so TLS
 * stands in for the signature check (§3.1.3.7 step 6; `WebAppConfig.isUsable`
 * requires HTTPS outside loopback). The API verifies every access token's
 * signature itself.
 */
internal object IdTokens {

    /** Allowed clock skew when comparing `exp` with the browser clock. */
    const val LEEWAY_SECONDS = 60L

    fun parse(jwt: String): IdTokenClaims? {
        val parts = jwt.split('.')
        if (parts.size != 3) return null
        val payload = Base64Url.decode(parts[1]) ?: return null
        val claims = try {
            Json.parseToJsonElement(payload.decodeToString()).jsonObject
        } catch (e: Exception) {
            return null
        }
        val audience = when (val aud = claims["aud"]) {
            is JsonPrimitive -> listOfNotNull(aud.stringOrNull())
            is JsonArray -> aud.map { (it as? JsonPrimitive)?.stringOrNull() ?: return null }
            else -> return null
        }
        return IdTokenClaims(
            issuer = claims.string("iss") ?: return null,
            subject = claims.string("sub")?.takeIf { it.isNotBlank() } ?: return null,
            audience = audience,
            authorizedParty = claims.string("azp"),
            expiresAtSeconds = (claims["exp"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null,
            nonce = claims.string("nonce"),
        )
    }

    /**
     * @param expectedNonce the nonce sent with the authorization request.
     * @param requireNonce true for the code exchange; on refresh a nonce may be
     *   absent but must match when present (§12.2).
     * @param expectedSubject the session's `sub` when refreshing.
     */
    fun isValid(
        claims: IdTokenClaims,
        config: WebAppConfig,
        nowSeconds: Long,
        expectedNonce: String,
        requireNonce: Boolean,
        expectedSubject: String? = null,
    ): Boolean {
        val nonceOk = when (val nonce = claims.nonce) {
            null -> !requireNonce
            else -> constantTimeEquals(nonce, expectedNonce)
        }
        val azpOk = claims.authorizedParty == null || claims.authorizedParty == config.clientId
        return claims.issuer == config.issuer &&
            claims.audience.singleOrNull() == config.clientId &&
            azpOk &&
            claims.expiresAtSeconds + LEEWAY_SECONDS > nowSeconds &&
            (expectedSubject == null || claims.subject == expectedSubject) &&
            nonceOk
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.stringOrNull()

    private fun JsonPrimitive.stringOrNull(): String? = if (isString) content else null
}

internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
