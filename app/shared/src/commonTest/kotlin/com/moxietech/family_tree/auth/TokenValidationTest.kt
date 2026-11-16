package com.moxietech.family_tree.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Builds unsigned test JWTs; only the payload matters to the client-side checks. */
internal fun testJwt(payload: String): String {
    fun part(json: String) = Base64Url.encode(json.encodeToByteArray())
    return part("""{"alg":"RS256"}""") + "." + part(payload) + ".signature"
}

internal const val TEST_ISSUER = "http://localhost:8081/realms/family-tree"
internal const val TEST_CLIENT = "family-tree-web"
internal const val TEST_NOW = 1_800_000_000L

internal fun idTokenPayload(
    sub: String = "user-1",
    nonce: String? = "nonce-1",
    iss: String = TEST_ISSUER,
    aud: String = "\"$TEST_CLIENT\"",
    azp: String? = TEST_CLIENT,
    exp: Long = TEST_NOW + 300,
): String = buildString {
    append("""{"iss":"$iss","sub":"$sub","aud":$aud,"exp":$exp,"iat":${exp - 300},"typ":"ID"""")
    if (nonce != null) append(""","nonce":"$nonce"""")
    if (azp != null) append(""","azp":"$azp"""")
    append('}')
}

class TokenValidationTest {

    private val config = WebAppConfig(TEST_ISSUER, TEST_CLIENT)

    private fun valid(payload: String, requireNonce: Boolean = true, subject: String? = null): Boolean {
        val claims = IdTokens.parse(testJwt(payload)) ?: return false
        return IdTokens.isValid(claims, config, TEST_NOW, "nonce-1", requireNonce, subject)
    }

    @Test
    fun keycloakShapedIdTokenPasses() {
        assertTrue(valid(idTokenPayload()))
        assertTrue(valid(idTokenPayload(aud = """["$TEST_CLIENT"]""")))
    }

    @Test
    fun issuerAudienceAndAuthorizedPartyAreChecked() {
        assertFalse(valid(idTokenPayload(iss = "http://localhost:8081/realms/other")))
        assertFalse(valid(idTokenPayload(aud = "\"family-tree-server\"")))
        assertFalse(valid(idTokenPayload(aud = """["$TEST_CLIENT","account"]""")), "only the configured browser client is trusted")
        assertFalse(valid(idTokenPayload(azp = "admin-cli")))
        assertTrue(valid(idTokenPayload(azp = null)), "a single audience may omit azp")
    }

    @Test
    fun expiryAllowsOnlySmallClockSkew() {
        assertTrue(valid(idTokenPayload(exp = TEST_NOW - IdTokens.LEEWAY_SECONDS + 1)))
        assertFalse(valid(idTokenPayload(exp = TEST_NOW - IdTokens.LEEWAY_SECONDS)))
        assertNull(IdTokens.parse(testJwt(idTokenPayload().replace(""""exp":${TEST_NOW + 300}""", """"exp":"soon""""))))
    }

    @Test
    fun nonceIsRequiredAtSignInAndMustMatchWhenPresentOnRefresh() {
        assertFalse(valid(idTokenPayload(nonce = null)))
        assertFalse(valid(idTokenPayload(nonce = "nonce-2")))
        assertTrue(valid(idTokenPayload(nonce = null), requireNonce = false))
        assertFalse(valid(idTokenPayload(nonce = "nonce-2"), requireNonce = false))
    }

    @Test
    fun refreshedTokenMustKeepTheSubject() {
        assertTrue(valid(idTokenPayload(), requireNonce = false, subject = "user-1"))
        assertFalse(valid(idTokenPayload(sub = "user-2"), requireNonce = false, subject = "user-1"))
    }

    @Test
    fun malformedTokensDoNotParse() {
        assertNull(IdTokens.parse("not-a-jwt"))
        assertNull(IdTokens.parse("a.b"))
        assertNull(IdTokens.parse("a.%%%.c"))
        assertNull(IdTokens.parse(testJwt("""{"iss":"$TEST_ISSUER","aud":"$TEST_CLIENT","exp":1}""")), "no sub")
        assertNull(IdTokens.parse(testJwt(idTokenPayload(sub = " "))))
    }

    @Test
    fun tokenResponsesMustBeUsableBearerTokens() {
        val good = TokenResponse("access", "Bearer", 300, "refresh", 1800, "id")
        assertTrue(good.isUsable())
        assertTrue(good.copy(tokenType = "bearer", refreshToken = null).isUsable())
        assertFalse(good.copy(accessToken = " ").isUsable())
        assertFalse(good.copy(tokenType = "mac").isUsable())
        assertFalse(good.copy(expiresIn = 0).isUsable())
        assertFalse(good.copy(refreshToken = "").isUsable())
        assertFalse(good.copy(idToken = "").isUsable())
        assertEquals(TEST_CLIENT, IdTokens.parse(testJwt(idTokenPayload()))?.authorizedParty)
    }
}
