package com.moxietech.family_tree.auth

import com.moxietech.family_tree.FakeBridge
import com.moxietech.family_tree.RFC7636_CHALLENGE
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PkceTest {

    private val rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

    @Test
    fun challengeIsTheBase64UrlOfWebCryptosDigestOfTheVerifier() = runTest {
        val bridge = FakeBridge()

        assertEquals(RFC7636_CHALLENGE, Pkce.s256Challenge(bridge, rfcVerifier))
        assertEquals(listOf(rfcVerifier), bridge.digestInputs)
    }

    @Test
    fun cryptoFailuresSurfaceAsBrowserFeatureErrors() = runTest {
        val blocked = FakeBridge().apply { cryptoBlocked = true }
        assertFailsWith<BrowserFeatureException> { Pkce.s256Challenge(blocked, rfcVerifier) }

        val short = FakeBridge().apply { digestResult = ByteArray(16) }
        assertFailsWith<BrowserFeatureException> { Pkce.s256Challenge(short, rfcVerifier) }
    }

    @Test
    fun verifierFromThirtyTwoRandomBytesHasTheRecommendedLength() {
        assertEquals(43, Pkce.randomToken(FakeBridge(), Pkce.VERIFIER_BYTES).length)
    }

    @Test
    fun base64UrlComesFromTheStandardLibraryWithoutPadding() {
        // RFC 7636 Appendix B: these 32 octets encode to the example verifier.
        val octets = intArrayOf(
            116, 24, 223, 180, 151, 153, 224, 37, 79, 250, 96, 125, 216, 173, 187, 186,
            22, 212, 37, 77, 105, 214, 191, 240, 91, 88, 5, 88, 83, 132, 141, 121,
        ).map { it.toByte() }.toByteArray()
        assertEquals(rfcVerifier, Base64Url.encode(octets))
        assertContentEquals(octets, Base64Url.decode(rfcVerifier))
        assertFalse('=' in Base64Url.encode(ByteArray(4)))
        assertNull(Base64Url.decode("a*b"))
    }

    @Test
    fun urlPartsSplitsWithoutTreatingRoutesAsCallbacks() {
        val callback = UrlParts("http://localhost:3000/?x=1#state=s%2B1&code=abc")
        assertEquals("http://localhost:3000/", callback.base)
        assertFalse(callback.fragmentIsRoute)
        assertEquals("s+1", callback.fragmentParameters["state"])

        val route = UrlParts("http://localhost:3000/#/trees/t1?view=ancestors")
        assertTrue(route.fragmentIsRoute)
        assertEquals("/trees/t1?view=ancestors", route.fragment)
    }

    @Test
    fun configRequiresHttpsOutsideLoopback() {
        fun config(issuer: String, api: String = "") = WebAppConfig(issuer, "family-tree-web", api)

        assertTrue(config("https://id.example.org/realms/family-tree").isUsable())
        assertTrue(config("http://localhost:8081/realms/family-tree").isUsable())
        assertTrue(config("http://127.0.0.1:8081/realms/family-tree").isUsable())
        assertFalse(config("http://id.example.org/realms/family-tree").isUsable())
        assertFalse(config("http://localhost.example.org/realms/family-tree").isUsable())
        assertFalse(config("localhost:8081/realms/family-tree").isUsable())
        assertFalse(config("https://id.example.org/realms/family-tree", api = "http://api.example.org").isUsable())
        assertFalse(WebAppConfig("https://id.example.org/realms/x", " ").isUsable())
    }
}
