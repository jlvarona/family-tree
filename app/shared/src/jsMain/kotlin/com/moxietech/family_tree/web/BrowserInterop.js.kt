package com.moxietech.family_tree.web

// Kept identical to BrowserInterop.wasmJs.kt: each js() call is the whole body of a
// top-level function, ES5 syntax only, so both compilers accept the same code.

private fun jsOrigin(): String = js("window.location.origin")

private fun jsHref(): String = js("window.location.href")

private fun jsAssign(url: String): Unit = js("window.location.assign(url)")

private fun jsReplaceState(url: String): Unit = js("window.history.replaceState(null, '', url)")

private fun jsSetHash(hash: String): Unit = js("(function () { window.location.hash = hash; })()")

private fun jsOnHashChange(listener: () -> Unit): Unit =
    js("window.addEventListener('hashchange', function () { listener(); })")

private fun jsSessionGet(key: String): String? =
    js("(function () { try { return window.sessionStorage.getItem(key); } catch (e) { return null; } })()")

private fun jsSessionSet(key: String, value: String): Unit =
    js("(function () { try { window.sessionStorage.setItem(key, value); } catch (e) { } })()")

private fun jsSessionRemove(key: String): Unit =
    js("(function () { try { window.sessionStorage.removeItem(key); } catch (e) { } })()")

private fun jsRandomHex(count: Int): String =
    js("Array.prototype.map.call(window.crypto.getRandomValues(new Uint8Array(count)), function (b) { return ('0' + b.toString(16)).slice(-2); }).join('')")

internal actual fun windowOrigin(): String = jsOrigin()
internal actual fun windowHref(): String = jsHref()
internal actual fun windowAssign(url: String) = jsAssign(url)
internal actual fun windowReplaceState(url: String) = jsReplaceState(url)
internal actual fun windowSetHash(hash: String) = jsSetHash(hash)
internal actual fun windowOnHashChange(listener: () -> Unit) = jsOnHashChange(listener)
internal actual fun sessionStorageGet(key: String): String? = jsSessionGet(key)
internal actual fun sessionStorageSet(key: String, value: String) = jsSessionSet(key, value)
internal actual fun sessionStorageRemove(key: String) = jsSessionRemove(key)
internal actual fun cryptoRandomHex(count: Int): String = jsRandomHex(count)
