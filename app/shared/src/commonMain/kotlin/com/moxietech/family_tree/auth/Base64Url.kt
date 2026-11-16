package com.moxietech.family_tree.auth

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Unpadded base64url (RFC 4648 §5) for PKCE and JWT segments, from the Kotlin standard library. */
@OptIn(ExperimentalEncodingApi::class)
internal object Base64Url {

    private val codec = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    fun encode(bytes: ByteArray): String = codec.encode(bytes)

    /** Null for anything that isn't base64url. */
    fun decode(text: String): ByteArray? =
        try {
            codec.decode(text)
        } catch (e: IllegalArgumentException) {
            null
        }
}
