package com.moxietech.family_tree.web

import com.moxietech.family_tree.auth.BrowserBridge
import com.moxietech.family_tree.auth.BrowserFeatureException
import com.moxietech.family_tree.auth.PickedFile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Thin `window` primitives, implemented with js() for each web target
// (BrowserInterop.js.kt, BrowserInterop.wasmJs.kt). Only strings, booleans and
// callbacks cross the boundary; JS exceptions are caught on the JS side.
internal expect fun windowOrigin(): String
internal expect fun windowHref(): String
internal expect fun windowAssign(url: String)
internal expect fun windowReplaceState(url: String)
internal expect fun windowSetHash(hash: String)
internal expect fun windowOnHashChange(listener: () -> Unit)

/** False when sessionStorage throws (blocked site data, some private modes). */
internal expect fun sessionStorageWorks(): Boolean
internal expect fun sessionStorageGet(key: String): String?

/** False when the write was refused (quota, blocked storage). */
internal expect fun sessionStorageSet(key: String, value: String): Boolean
internal expect fun sessionStorageRemove(key: String)

/** `crypto.getRandomValues` over [count] bytes as lowercase hex, or "" if unavailable. */
internal expect fun cryptoRandomHex(count: Int): String

/**
 * `crypto.subtle.digest('SHA-256', UTF-8 of text)`; calls [onDone] with lowercase
 * hex, or [onError] (insecure context, no SubtleCrypto, rejected promise).
 */
internal expect fun cryptoSha256Hex(text: String, onDone: (String) -> Unit, onError: (String) -> Unit)

/**
 * Opens a one-off `<input type=file accept=…>`. When a file is chosen it checks
 * `File.size` and `File.type` first: only a file of at most [maxBytes] bytes whose type
 * is in the comma-separated [accept] list is read (`FileReader.readAsDataURL`), and
 * [onDone] gets (type, size, true, base64). Any other file is never opened and [onDone]
 * gets (type, size, false, ""). Nothing is called if the user cancels or reading fails.
 * The size travels as a Double (exact for any real file size).
 */
internal expect fun pickFileBase64(accept: String, maxBytes: Double, onDone: (String, Double, Boolean, String) -> Unit)

/** [BrowserBridge] over the page's `window`. */
internal class WindowBridge : BrowserBridge {
    override val origin: String get() = windowOrigin()

    override fun currentUrl(): String = windowHref()

    override fun replaceUrl(url: String) = windowReplaceState(url)

    override fun navigate(url: String) = windowAssign(url)

    override fun setHash(hash: String) = windowSetHash(hash)

    override fun onHashChange(listener: () -> Unit) = windowOnHashChange(listener)

    override fun sessionGet(key: String): String? {
        if (!sessionStorageWorks()) throw BrowserFeatureException("sessionStorage")
        return sessionStorageGet(key)
    }

    override fun sessionSet(key: String, value: String) {
        if (!sessionStorageSet(key, value)) throw BrowserFeatureException("sessionStorage")
    }

    override fun sessionRemove(key: String) = sessionStorageRemove(key)

    override fun randomBytes(count: Int): ByteArray = hexToBytes(cryptoRandomHex(count), count, "crypto.getRandomValues")

    override suspend fun sha256(text: String): ByteArray {
        val hex = suspendCancellableCoroutine { continuation ->
            cryptoSha256Hex(
                text,
                onDone = { if (continuation.isActive) continuation.resume(it) },
                onError = {
                    if (continuation.isActive) continuation.resumeWithException(BrowserFeatureException("crypto.subtle.digest"))
                },
            )
        }
        return hexToBytes(hex, 32, "crypto.subtle.digest")
    }

    @OptIn(ExperimentalEncodingApi::class)
    override fun pickFile(accept: List<String>, maxBytes: Long, onPicked: (PickedFile) -> Unit) {
        pickFileBase64(accept.joinToString(","), maxBytes.toDouble()) { type, size, read, base64 ->
            val bytes = if (read) runCatching { Base64.decode(base64) }.getOrNull() ?: return@pickFileBase64 else null
            onPicked(PickedFile(type, size.toLong(), bytes))
        }
    }

    private fun hexToBytes(hex: String, count: Int, feature: String): ByteArray {
        if (hex.length != count * 2) throw BrowserFeatureException(feature)
        return ByteArray(count) { i ->
            hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: throw BrowserFeatureException(feature)
        }
    }
}
