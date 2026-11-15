package com.moxietech.family_tree.server.config

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Base64

/**
 * A controlled stand-in for Keycloak's JWKS endpoint: serves one test RSA key
 * over HTTP on a loopback ephemeral port, so the production verifier fetches
 * it exactly as it would fetch Keycloak's `/protocol/openid-connect/certs`.
 * Tokens are signed with keys generated for this test run only.
 */
class TestJwks : AutoCloseable {
    val keyId = "test-key-1"
    private val keyPair: KeyPair = rsaKeyPair()

    /** A different key that claims the same `kid`: its signatures must not verify. */
    private val rogueKeyPair: KeyPair = rsaKeyPair()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/certs") { exchange ->
            val body = jwksJson().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    val jwksUri: String = "http://${server.address.hostString}:${server.address.port}/certs"

    /**
     * Signs a token shaped like a Keycloak access token for the browser client.
     * [customize] runs last, so it can override or remove the defaults.
     */
    fun accessToken(
        issuer: String,
        audience: Array<String>? = arrayOf(DEFAULT_API_AUDIENCE, "account"),
        type: String? = ACCESS_TOKEN_TYPE,
        expiresAt: Instant? = Instant.now().plusSeconds(300),
        email: String = "dev@example.com",
        signWithRogueKey: Boolean = false,
        customize: JWTCreator.Builder.() -> Unit = {},
    ): String {
        val now = Instant.now()
        val builder = JWT.create()
            .withKeyId(keyId)
            .withIssuer(issuer)
            .withSubject("subject-$email")
            .withIssuedAt(now.minusSeconds(1))
            .withClaim("azp", "family-tree-web")
            .withClaim("email", email)
            .withClaim("preferred_username", email)
        if (audience != null) builder.withAudience(*audience)
        if (type != null) builder.withClaim("typ", type)
        if (expiresAt != null) builder.withExpiresAt(expiresAt)
        builder.customize()
        val keys = if (signWithRogueKey) rogueKeyPair else keyPair
        return builder.sign(Algorithm.RSA256(keys.public as RSAPublicKey, keys.private as RSAPrivateKey))
    }

    private fun jwksJson(): String {
        val key = keyPair.public as RSAPublicKey
        val n = base64Url(key.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray())
        val e = base64Url(key.publicExponent.toByteArray())
        return """{"keys":[{"kty":"RSA","kid":"$keyId","use":"sig","alg":"RS256","n":"$n","e":"$e"}]}"""
    }

    override fun close() = server.stop(0)

    private companion object {
        fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
