package com.moxietech.family_tree.auth

/**
 * Unpadded base64url (RFC 4648 §5), the encoding PKCE (RFC 7636) and JWTs use.
 * Kept here, small and tested, so the sign-in code doesn't depend on which
 * stdlib Base64 options are stable in a given Kotlin version.
 */
internal object Base64Url {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = (bytes[i].u() shl 16) or (bytes[i + 1].u() shl 8) or bytes[i + 2].u()
            out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                .append(ALPHABET[n ushr 6 and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].u() shl 16
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
            }
            2 -> {
                val n = (bytes[i].u() shl 16) or (bytes[i + 1].u() shl 8)
                out.append(ALPHABET[n ushr 18 and 63]).append(ALPHABET[n ushr 12 and 63])
                    .append(ALPHABET[n ushr 6 and 63])
            }
        }
        return out.toString()
    }

    /** Decodes unpadded or padded base64url; returns null for anything else. */
    fun decode(text: String): ByteArray? {
        val clean = text.trimEnd('=')
        if (clean.length % 4 == 1) return null
        val out = ByteArray(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var index = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[index++] = (buffer ushr bits and 0xFF).toByte()
            }
        }
        return out
    }

    private fun Byte.u(): Int = toInt() and 0xFF
}
