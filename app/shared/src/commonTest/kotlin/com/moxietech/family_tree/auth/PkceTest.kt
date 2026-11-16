package com.moxietech.family_tree.auth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PkceTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun sha256MatchesFipsVectors() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(Sha256.digest(ByteArray(0))),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(Sha256.digest("abc".encodeToByteArray())),
        )
        // Two blocks: the padding spills into a second 64-byte block.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(Sha256.digest("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())),
        )
    }

    @Test
    fun s256ChallengeMatchesRfc7636AppendixB() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.s256Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun base64UrlRoundTripsWithoutPadding() {
        for (size in 0..9) {
            val bytes = ByteArray(size) { (it * 37 + 250).toByte() }
            val encoded = Base64Url.encode(bytes)
            assertEquals(false, encoded.contains('=') || encoded.contains('+') || encoded.contains('/'))
            assertContentEquals(bytes, Base64Url.decode(encoded))
        }
        // RFC 7636 Appendix B: these 32 octets encode to the example verifier.
        val octets = intArrayOf(
            116, 24, 223, 180, 151, 153, 224, 37, 79, 250, 96, 125, 216, 173, 187, 186,
            22, 212, 37, 77, 105, 214, 191, 240, 91, 88, 5, 88, 83, 132, 141, 121,
        ).map { it.toByte() }.toByteArray()
        assertEquals("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", Base64Url.encode(octets))
        assertNull(Base64Url.decode("a*b"))
    }

    @Test
    fun verifierFromThirtyTwoRandomBytesHasTheRecommendedLength() {
        val bridge = com.moxietech.family_tree.FakeBridge()
        assertEquals(43, Pkce.randomToken(bridge, Pkce.VERIFIER_BYTES).length)
    }

    @Test
    fun urlCodecEncodesReservedCharactersAndParsesQueries() {
        assertEquals("a%20b%26c%3Dd%2F%C3%A9", UrlCodec.encode("a b&c=d/é"))
        assertEquals("a b&c=d/é", UrlCodec.decode("a%20b%26c%3Dd%2F%C3%A9"))
        val url = ParsedUrl.parse("http://localhost:3000/?state=s%2B1&code=abc#/trees/t1")
        assertEquals("http://localhost:3000/", url.base)
        assertEquals(mapOf("state" to "s+1", "code" to "abc"), url.query)
        assertEquals("/trees/t1", url.fragment)
    }
}
