@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.moxietech.family_tree.web

// Kept identical to BrowserInterop.js.kt: each js() call is the whole body of a
// top-level function (a Kotlin/Wasm requirement), ES5 syntax only. JS exceptions are
// caught here and reported as values.

private fun jsOrigin(): String = js("window.location.origin")

private fun jsHref(): String = js("window.location.href")

private fun jsAssign(url: String): Unit = js("window.location.assign(url)")

private fun jsReplaceState(url: String): Unit = js("window.history.replaceState(null, '', url)")

private fun jsSetHash(hash: String): Unit = js("(function () { window.location.hash = hash; })()")

private fun jsOnHashChange(listener: () -> Unit): Unit =
    js("window.addEventListener('hashchange', function () { listener(); })")

private fun jsSessionWorks(): Boolean =
    js("(function () { try { var k = '__family_tree_probe__'; window.sessionStorage.setItem(k, '1'); window.sessionStorage.removeItem(k); return true; } catch (e) { return false; } })()")

private fun jsSessionGet(key: String): String? =
    js("(function () { try { return window.sessionStorage.getItem(key); } catch (e) { return null; } })()")

private fun jsSessionSet(key: String, value: String): Boolean =
    js("(function () { try { window.sessionStorage.setItem(key, value); return true; } catch (e) { return false; } })()")

private fun jsSessionRemove(key: String): Unit =
    js("(function () { try { window.sessionStorage.removeItem(key); } catch (e) { } })()")

private fun jsRandomHex(count: Int): String =
    js("(function () { try { return Array.prototype.map.call(window.crypto.getRandomValues(new Uint8Array(count)), function (b) { return ('0' + b.toString(16)).slice(-2); }).join(''); } catch (e) { return ''; } })()")

private fun jsSha256Hex(text: String, onDone: (String) -> Unit, onError: (String) -> Unit): Unit =
    js("(function () { try { window.crypto.subtle.digest('SHA-256', new TextEncoder().encode(text)).then(function (buffer) { onDone(Array.prototype.map.call(new Uint8Array(buffer), function (b) { return ('0' + b.toString(16)).slice(-2); }).join('')); }, function () { onError('digest'); }); } catch (e) { onError('digest'); } })()")

private fun jsPickFile(accept: String, onDone: (String, String) -> Unit): Unit =
    js("(function () { try { var input = document.createElement('input'); input.type = 'file'; input.accept = accept; input.style.display = 'none'; input.addEventListener('change', function () { var file = input.files && input.files[0]; if (input.parentNode) { input.parentNode.removeChild(input); } if (!file) { return; } var reader = new FileReader(); reader.onload = function () { var url = String(reader.result); var comma = url.indexOf(','); if (comma >= 0) { onDone(file.type || '', url.substring(comma + 1)); } }; reader.readAsDataURL(file); }); document.body.appendChild(input); input.click(); } catch (e) { } })()")

internal actual fun pickFileBase64(accept: String, onDone: (String, String) -> Unit) = jsPickFile(accept, onDone)

internal actual fun windowOrigin(): String = jsOrigin()
internal actual fun windowHref(): String = jsHref()
internal actual fun windowAssign(url: String) = jsAssign(url)
internal actual fun windowReplaceState(url: String) = jsReplaceState(url)
internal actual fun windowSetHash(hash: String) = jsSetHash(hash)
internal actual fun windowOnHashChange(listener: () -> Unit) = jsOnHashChange(listener)
internal actual fun sessionStorageWorks(): Boolean = jsSessionWorks()
internal actual fun sessionStorageGet(key: String): String? = jsSessionGet(key)
internal actual fun sessionStorageSet(key: String, value: String): Boolean = jsSessionSet(key, value)
internal actual fun sessionStorageRemove(key: String) = jsSessionRemove(key)
internal actual fun cryptoRandomHex(count: Int): String = jsRandomHex(count)
internal actual fun cryptoSha256Hex(text: String, onDone: (String) -> Unit, onError: (String) -> Unit) =
    jsSha256Hex(text, onDone, onError)
