package com.moxietech.family_tree

import com.moxietech.family_tree.server.collaboration.ChangeProposalSettings
import com.moxietech.family_tree.server.collaboration.changeProposalRoutes
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
import com.moxietech.family_tree.server.media.MediaService
import com.moxietech.family_tree.server.media.MediaSettings
import com.moxietech.family_tree.server.media.S3ObjectStore
import com.moxietech.family_tree.server.media.mediaRoutes
import com.moxietech.family_tree.server.sources.citationRoutes
import com.moxietech.family_tree.server.sources.sourceRoutes
import com.moxietech.family_tree.server.trees.InvitationService
import com.moxietech.family_tree.server.trees.InvitationSettings
import com.moxietech.family_tree.server.trees.MemberService
import com.moxietech.family_tree.server.trees.invitationRoutes
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

fun main() {
    val dataSource = DatabaseConfig.createDataSource(DatabaseSettings())
    DatabaseConfig.migrate(dataSource)
    DatabaseConfig.connectExposed(dataSource)
    AppDatabase.init(dataSource)

    val mediaSettings = MediaSettings()
    val store = S3ObjectStore(mediaSettings)
    if (mediaSettings.createBucket) {
        // Local development only (README "Photo storage"): bucket and browser CORS rules.
        runBlocking {
            store.ensureBucket()
            store.putLocalCors(mediaSettings.localCorsOrigins)
        }
    }
    val media = MediaService(lazyOf(store), mediaSettings)

    embeddedServer(Netty, port = 8080, host = "0.0.0.0") { module(media = media) }
        .start(wait = true)
}

/**
 * [media] defaults to a store built from the environment only when a media route first
 * needs it, so modules that never touch photos (most tests) open no S3 client.
 */
fun Application.module(
    oidc: OidcSettings = OidcSettings(),
    media: MediaService = MediaService(lazy { S3ObjectStore(MediaSettings()) }),
    // FT-011: off unless INVITATIONS_ENABLED=true (README "Invitations").
    invitationSettings: InvitationSettings = InvitationSettings(),
    invitations: InvitationService = InvitationService(invitationSettings),
) {
    // FT-014: proposals are the other half of sharing and follow INVITATIONS_ENABLED.
    val proposals = ChangeProposalSettings.forSharing(invitationSettings)
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
        treeRoutes(sharingEnabled = proposals.enabled)
        personRoutes(proposals)
        unionRoutes(proposals)
        eventRoutes(proposals)
        placeRoutes()
        sourceRoutes()
        citationRoutes(proposals)
        graphRoutes()
        mediaRoutes(media)
        invitationRoutes(invitations, MemberService(invitationSettings))
        changeProposalRoutes(proposals)
    }
}
