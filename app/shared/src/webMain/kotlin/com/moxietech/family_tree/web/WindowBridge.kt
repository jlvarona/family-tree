package com.moxietech.family_tree.web

import com.moxietech.family_tree.auth.BrowserBridge

// Thin `window` primitives, implemented with js() for each web target
// (BrowserInterop.js.kt, BrowserInterop.wasmJs.kt). Only strings cross the boundary.
internal expect fun windowOrigin(): String
internal expect fun windowHref(): String
internal expect fun windowAssign(url: String)
internal expect fun windowReplaceState(url: String)
internal expect fun windowSetHash(hash: String)
internal expect fun windowOnHashChange(listener: () -> Unit)
internal expect fun sessionStorageGet(key: String): String?
internal expect fun sessionStorageSet(key: String, value: String)
internal expect fun sessionStorageRemove(key: String)

/** `crypto.getRandomValues` over [count] bytes, as lowercase hex. */
internal expect fun cryptoRandomHex(count: Int): String

/** [BrowserBridge] over the page's `window`. */
internal class WindowBridge : BrowserBridge {
    override val origin: String get() = windowOrigin()

    override fun currentUrl(): String = windowHref()

    override fun replaceUrl(url: String) = windowReplaceState(url)

    override fun navigate(url: String) = windowAssign(url)

    override fun setHash(hash: String) = windowSetHash(hash)

    override fun onHashChange(listener: () -> Unit) = windowOnHashChange(listener)

    override fun sessionGet(key: String): String? = sessionStorageGet(key)

    override fun sessionSet(key: String, value: String) = sessionStorageSet(key, value)

    override fun sessionRemove(key: String) = sessionStorageRemove(key)

    override fun randomBytes(count: Int): ByteArray {
        val hex = cryptoRandomHex(count)
        check(hex.length == count * 2) { "Web Crypto returned an unexpected value" }
        return ByteArray(count) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
