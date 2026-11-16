package com.moxietech.family_tree.auth

/** RFC 7636 helpers for the authorization code flow with PKCE (S256 only). */
internal object Pkce {

    /** 32 random bytes → a 43-character verifier, the RFC's recommended size. */
    const val VERIFIER_BYTES = 32

    /** State and nonce use the same amount of randomness as the verifier. */
    const val STATE_BYTES = 32

    fun randomToken(bridge: BrowserBridge, byteCount: Int): String {
        val bytes = bridge.randomBytes(byteCount)
        check(bytes.size == byteCount) { "The browser returned too few random bytes" }
        return Base64Url.encode(bytes)
    }

    /** `BASE64URL(SHA256(ASCII(code_verifier)))`. */
    fun s256Challenge(verifier: String): String = Base64Url.encode(Sha256.digest(verifier.encodeToByteArray()))
}
