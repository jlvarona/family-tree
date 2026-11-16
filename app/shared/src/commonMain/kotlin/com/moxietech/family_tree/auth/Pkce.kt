package com.moxietech.family_tree.auth

/** RFC 7636 helpers for the authorization code flow with PKCE (S256 only). */
internal object Pkce {

    /** 32 random bytes → a 43-character verifier, the RFC's recommended size. */
    const val VERIFIER_BYTES = 32

    /** State and nonce use the same amount of randomness as the verifier. */
    const val STATE_BYTES = 32

    fun randomToken(bridge: BrowserBridge, byteCount: Int): String {
        val bytes = bridge.randomBytes(byteCount)
        if (bytes.size != byteCount) throw BrowserFeatureException("crypto.getRandomValues")
        return Base64Url.encode(bytes)
    }

    /** `BASE64URL(SHA256(ASCII(code_verifier)))`, hashed by Web Crypto. */
    suspend fun s256Challenge(bridge: BrowserBridge, verifier: String): String {
        val digest = bridge.sha256(verifier)
        if (digest.size != 32) throw BrowserFeatureException("crypto.subtle.digest")
        return Base64Url.encode(digest)
    }
}
