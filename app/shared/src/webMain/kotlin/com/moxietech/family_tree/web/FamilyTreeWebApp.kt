package com.moxietech.family_tree.web

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.moxietech.family_tree.auth.BrowserBridge
import com.moxietech.family_tree.auth.UrlParts
import com.moxietech.family_tree.auth.SessionManager
import com.moxietech.family_tree.auth.WebAppConfig
import com.moxietech.family_tree.client.HttpFamilyTreeApi
import com.moxietech.family_tree.client.HttpMediaTransfer
import com.moxietech.family_tree.state.FamilyTreeController
import com.moxietech.family_tree.ui.FamilyTreeApp
import com.moxietech.family_tree.ui.FamilyTreeTheme
import com.moxietech.family_tree.ui.LoadingPane
import com.moxietech.family_tree.ui.MessagePane
import com.moxietech.family_tree.ui.Palette
import family_tree.app.shared.generated.resources.Res
import family_tree.app.shared.generated.resources.config_error
import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * The browser app: loads `config.json`, then wires the Keycloak session, the
 * API client and the controller to the shared UI. The web entry point
 * (`:app:webApp`) only mounts this.
 */
@Composable
fun FamilyTreeWebApp() {
    val scope = rememberCoroutineScope()
    val bridge = remember { WindowBridge() }
    val http = remember { HttpClient(Js) }
    var controller by remember { mutableStateOf<FamilyTreeController?>(null) }
    var configFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val config = loadConfig(http, bridge)
        if (config == null) {
            configFailed = true
        } else {
            val session = SessionManager(config, bridge, http, scope)
            val api = HttpFamilyTreeApi(http, config.apiBaseUrl.ifBlank { bridge.origin }, session)
            // FT-008: image bytes go straight to the object store's presigned URLs, on a
            // client that never carries the session token.
            val transfer = HttpMediaTransfer(HttpClient(Js))
            controller = FamilyTreeController(session, api, bridge, scope, transfer).also { it.start() }
        }
    }

    val current = controller
    if (current != null) {
        FamilyTreeApp(current)
    } else {
        FamilyTreeTheme {
            Surface(Modifier.fillMaxSize(), color = Palette.background) {
                if (configFailed) MessagePane(Res.string.config_error) else LoadingPane()
            }
        }
    }
}

private val configJson = Json { ignoreUnknownKeys = true }

/**
 * `config.json` next to the page; null if it's missing, malformed, or points at
 * a non-HTTPS issuer or API outside loopback (`WebAppConfig.isUsable`).
 */
private suspend fun loadConfig(http: HttpClient, bridge: BrowserBridge): WebAppConfig? {
    val pageBase = UrlParts(bridge.currentUrl()).base.substringBeforeLast('/')
    return try {
        val response = http.get("$pageBase/config.json")
        if (!response.status.isSuccess()) return null
        configJson.decodeFromString(WebAppConfig.serializer(), response.bodyAsText()).takeIf { it.isUsable() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
