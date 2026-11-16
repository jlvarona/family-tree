package com.moxietech.family_tree

import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.config.OidcSettings
import com.moxietech.family_tree.server.config.configureErrorMapping
import com.moxietech.family_tree.server.config.configureOidc
import com.moxietech.family_tree.server.graph.eventRoutes
import com.moxietech.family_tree.server.graph.graphRoutes
import com.moxietech.family_tree.server.graph.personRoutes
import com.moxietech.family_tree.server.graph.placeRoutes
import com.moxietech.family_tree.server.graph.unionRoutes
import com.moxietech.family_tree.server.identity.accountRoutes
import com.moxietech.family_tree.server.sources.sourceRoutes
import com.moxietech.family_tree.server.trees.treeRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

fun main() {
    val dataSource = DatabaseConfig.createDataSource(DatabaseSettings())
    DatabaseConfig.migrate(dataSource)
    DatabaseConfig.connectExposed(dataSource)
    AppDatabase.init(dataSource)

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") { module() }
        .start(wait = true)
}

fun Application.module(oidc: OidcSettings = OidcSettings()) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    install(StatusPages) {
        configureErrorMapping()
    }
    install(CallLogging)
    install(Authentication) {
        configureOidc(oidc)
    }

    routing {
        accountRoutes()
        treeRoutes()
        personRoutes()
        unionRoutes()
        eventRoutes()
        placeRoutes()
        sourceRoutes()
        graphRoutes()
    }
}
