package com.moxietech.family_tree.server.permissions

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.api.CreateCitationRequest
import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.CreateGrantRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.api.CreateSourceRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.GrantDto
import com.moxietech.family_tree.api.MediaDownloadDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.api.UpdateEventRequest
import com.moxietech.family_tree.api.UpdatePersonRequest
import com.moxietech.family_tree.api.UpdateUnionRequest
import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.GrantId
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.server.config.ApiErrorResponse
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.config.configureErrorMapping
import com.moxietech.family_tree.server.graph.EventService
import com.moxietech.family_tree.server.graph.PersonRepository
import com.moxietech.family_tree.server.graph.PersonService
import com.moxietech.family_tree.server.graph.UnionService
import com.moxietech.family_tree.server.graph.eventRoutes
import com.moxietech.family_tree.server.graph.graphRoutes
import com.moxietech.family_tree.server.graph.personRoutes
import com.moxietech.family_tree.server.graph.unionRoutes
import com.moxietech.family_tree.server.identity.AccountRepository
import com.moxietech.family_tree.server.identity.OidcIdentity
import com.moxietech.family_tree.server.media.MediaService
import com.moxietech.family_tree.server.media.ObjectStore
import com.moxietech.family_tree.server.media.PresignedDownload
import com.moxietech.family_tree.server.media.PresignedUpload
import com.moxietech.family_tree.server.media.StoredBytes
import com.moxietech.family_tree.server.media.mediaRoutes
import com.moxietech.family_tree.server.sources.CitationService
import com.moxietech.family_tree.server.sources.SourceService
import com.moxietech.family_tree.server.sources.citationRoutes
import com.moxietech.family_tree.server.trees.MembershipRepository
import com.moxietech.family_tree.server.trees.TreeService
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * FT-013: grant-based living-person redaction, as a reproducible permission and privacy
 * scenario over HTTP through the production routes against PostgreSQL 16. The rule
 * table itself is `:core` `PersonVisibilityTest`; these cases show every response route
 * applies it from the grant scopes of the same snapshot (or, for writes, under the
 * write's locks).
 *
 * Every test builds its own tree. Everyone is living except gp1, gp2 and uncle:
 *
 *   gp1 + gp2 (1940)        ->  mum*, aunt*
 *   mum* + dad* (1975)      ->  me*, sib*
 *   aunt* + uncle (1970)    ->  cousin*
 *   out1* + out2* (1990)    ->  outKid*
 *
 * Members:
 * - VIEWER, linked to `me` (home branch: mum, dad, me, sib), VIEWER on aunt's
 *   descendants without partners: sees aunt and cousin, and none of their own family.
 * - CONTRIBUTOR, linked to `sib`, CONTRIBUTOR on mum's descendants with partners: sees
 *   mum, dad, me and sib.
 * - ADMIN, whole-tree ADMIN: sees everyone.
 * - LIMITED_ADMIN, an ADMIN grant on gp1's side stored directly: fails closed, sees no
 *   living person.
 * - NO_GRANT, active member without grants; INVITEE, INVITED with a whole-tree VIEWER
 *   grant (not a reader); OUTSIDER, a member of another tree with a whole-tree grant there.
 */
class GrantPrivacyTest {

    private enum class Actor(val email: String) {
        OWNER("ft013-owner@example.com"),
        VIEWER("ft013-viewer@example.com"),
        CONTRIBUTOR("ft013-contributor@example.com"),
        ADMIN("ft013-admin@example.com"),
        LIMITED_ADMIN("ft013-limited-admin@example.com"),
        NO_GRANT("ft013-no-grant@example.com"),
        INVITEE("ft013-invitee@example.com"),
        OUTSIDER("ft013-outsider@example.com"),
        HOME_ONLY("ft013-home-only@example.com"),
        REACHER("ft013-reacher@example.com"),
        PAIR_VIEWER("ft013-pair-viewer@example.com"),
    }

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val TEST_SECRET = "grant-privacy-test-only"
        private const val TEST_ISSUER = "https://issuer.test"
        private val json = Json { ignoreUnknownKeys = true }

        private lateinit var dataSource: HikariDataSource
        private lateinit var accounts: Map<Actor, Account>

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dataSource = DatabaseConfig.createDataSource(
                DatabaseSettings(jdbcUrl = postgres.jdbcUrl, user = postgres.username, password = postgres.password),
            )
            DatabaseConfig.migrate(dataSource)
            DatabaseConfig.connectExposed(dataSource)
            AppDatabase.init(dataSource)
            accounts = Actor.entries.associateWith {
                AccountRepository.getOrCreateByIdentity(OidcIdentity(TEST_ISSUER, it.email), it.email, it.name, Locale.EN)
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::dataSource.isInitialized) dataSource.close()
        }

        private fun token(actor: Actor): String =
            JWT.create().withIssuer(TEST_ISSUER).withSubject(actor.email).withClaim("email", actor.email)
                .sign(Algorithm.HMAC256(TEST_SECRET))

        private inline fun <reified T> encode(value: T): String = json.encodeToString(value)

        private val DECEASED = setOf("gp1", "gp2", "uncle")
        private val LABELS = listOf("gp1", "gp2", "mum", "aunt", "dad", "uncle", "me", "sib", "cousin", "out1", "out2", "outKid")
        private val LIVING = LABELS.toSet() - DECEASED

        /** The living people each reader sees in full; everyone else living is a placeholder. */
        private val VISIBLE_LIVING = mapOf(
            Actor.OWNER to LIVING,
            Actor.ADMIN to LIVING,
            Actor.VIEWER to setOf("aunt", "cousin"),
            Actor.CONTRIBUTOR to setOf("mum", "dad", "me", "sib"),
            Actor.LIMITED_ADMIN to emptySet(),
            Actor.NO_GRANT to emptySet(),
            Actor.HOME_ONLY to setOf("out1", "outKid"),
            Actor.REACHER to setOf("out1", "outKid", "out2"),
            Actor.PAIR_VIEWER to setOf("out1"),
        )
    }

    private val Actor.id get() = accounts.getValue(this).id
    private val Actor.uuid get() = Uuid.parse(id.value)

    private class Family(val tree: String) {
        val people = linkedMapOf<String, String>()
        val unions = linkedMapOf<String, String>()
        val events = linkedMapOf<String, String>()
        val grants = linkedMapOf<Actor, GrantId>()
        val extraGrants = linkedMapOf<String, GrantId>()
        val photos = linkedMapOf<String, String>()
        lateinit var source: String
        lateinit var otherTree: String
        fun id(label: String) = people.getValue(label)
        fun union(label: String) = unions.getValue(label)
        fun label(id: String) = people.entries.single { it.value == id }.key
        val path get() = "/api/v1/trees/$tree"
        val treeId get() = TreeId(tree)
        val treeUuid get() = Uuid.parse(tree)
    }

    private suspend fun newFamily(): Family {
        val owner = Actor.OWNER.id
        val f = Family(TreeService.createTree(owner, "FT-013 family").id.value)
        for (label in LABELS) {
            f.people[label] = PersonService.createPerson(
                f.treeId, owner,
                CreatePersonRequest(givenNames = label, surnamePaternal = "Varela", sex = "FEMALE", isLiving = label !in DECEASED, bio = "bio of $label"),
            ).first.id.value
        }
        suspend fun union(label: String, a: String, b: String, start: String, vararg kids: String) {
            val union = UnionService.createUnion(f.treeId, owner, CreateUnionRequest(f.id(a), f.id(b), "MARRIED", startDate = start))
            f.unions[label] = union.id
            for (kid in kids) UnionService.addChild(f.treeId, UnionId(union.id), owner, AddUnionChildRequest(f.id(kid), "BIRTH"))
        }
        union("G", "gp1", "gp2", "1940", "mum", "aunt")
        union("P", "mum", "dad", "1975", "me", "sib")
        union("A", "aunt", "uncle", "1970", "cousin")
        union("O", "out1", "out2", "1990", "outKid")

        // One dated, noted event per person, and one on each union.
        for (label in LABELS) {
            f.events[label] = EventService.createEvent(
                f.treeId, owner, CreateEventRequest(personId = f.id(label), type = "BIRTH", date = "1 JAN 1950", notes = "born $label"),
            ).id
        }
        for (label in f.unions.keys) {
            f.events["union $label"] = EventService.createEvent(
                f.treeId, owner, CreateEventRequest(unionId = f.union(label), type = "MARRIAGE", notes = "wedding $label"),
            ).id
        }
        f.source = SourceService.createSource(f.treeId, owner, CreateSourceRequest(title = "Parish register", type = "CHURCH_RECORD")).id
        suspend fun cite(type: String, target: String) =
            CitationService.createCitation(f.treeId, owner, CreateCitationRequest(f.source, type, target, page = "p. $target", confidence = 2))
        for (label in listOf("mum", "cousin", "gp1")) {
            cite("PERSON", f.id(label))
            cite("EVENT", f.events.getValue(label))
        }
        for (label in listOf("P", "A", "O")) cite("UNION", f.union(label))
        cite("EVENT", f.events.getValue("union O"))

        // A verified profile photo for mum and cousin (stored directly: the upload path is FT-008's).
        for (label in listOf("mum", "cousin")) f.photos[label] = readyPhoto(f, label)

        fun member(actor: Actor, person: String? = null, status: MembershipStatus = MembershipStatus.ACTIVE) =
            MembershipRepository.insert(f.treeUuid, actor.uuid, person?.let { Uuid.parse(f.id(it)) }, status)
        suspend fun grant(actor: Actor, role: String, anchor: String, direction: String, partners: Boolean = false) {
            f.grants[actor] = GrantId(
                GrantService.createGrant(f.treeId, owner, CreateGrantRequest(actor.id.value, role, f.id(anchor), direction, includePartners = partners)).id,
            )
        }
        member(Actor.VIEWER, "me")
        grant(Actor.VIEWER, "VIEWER", "aunt", "DESCENDANTS")
        member(Actor.CONTRIBUTOR, "sib")
        grant(Actor.CONTRIBUTOR, "CONTRIBUTOR", "mum", "DESCENDANTS", partners = true)
        member(Actor.ADMIN)
        grant(Actor.ADMIN, "ADMIN", "gp1", "WHOLE_TREE")
        member(Actor.LIMITED_ADMIN)
        // Stored before FT-012 could refuse it: an ADMIN grant on a limited branch.
        sql(
            "insert into tree_grant (id, tree_id, account_id, role, anchor_person_id, direction, include_partners, created_by_account_id) " +
                "values (gen_random_uuid(), ?, ?, 'ADMIN', ?, 'SIDE', true, ?)",
            UUID.fromString(f.tree), UUID.fromString(Actor.LIMITED_ADMIN.id.value), UUID.fromString(f.id("gp1")), UUID.fromString(owner.value),
        )
        member(Actor.NO_GRANT)
        // Linked to cousin (home branch: aunt, uncle, cousin), with an editing grant elsewhere:
        // edits aunt live through the home branch but can't see her.
        member(Actor.HOME_ONLY, "cousin")
        grant(Actor.HOME_ONLY, "CONTRIBUTOR", "out1", "DESCENDANTS")
        // Unlinked: edits out1's descendants (no partners), sees them with partners.
        member(Actor.REACHER)
        grant(Actor.REACHER, "CONTRIBUTOR", "out1", "DESCENDANTS")
        f.extraGrants["REACHER viewer"] = GrantId(
            GrantService.createGrant(f.treeId, owner, CreateGrantRequest(Actor.REACHER.id.value, "VIEWER", f.id("out1"), "DESCENDANTS", includePartners = true)).id,
        )
        // A VIEWER grant covering out1 alone (out1 has no parents).
        member(Actor.PAIR_VIEWER)
        grant(Actor.PAIR_VIEWER, "VIEWER", "out1", "ANCESTORS")
        member(Actor.INVITEE, status = MembershipStatus.INVITED)
        grant(Actor.INVITEE, "VIEWER", "gp1", "WHOLE_TREE")

        // OUTSIDER holds a whole-tree grant in another tree only.
        val other = TreeService.createTree(owner, "FT-013 other family")
        f.otherTree = other.id.value
        val stranger = PersonService.createPerson(other.id, owner, CreatePersonRequest("stranger", sex = "UNKNOWN", isLiving = true)).first.id.value
        MembershipRepository.insert(Uuid.parse(other.id.value), Actor.OUTSIDER.uuid, null, MembershipStatus.ACTIVE)
        GrantService.createGrant(other.id, owner, CreateGrantRequest(Actor.OUTSIDER.id.value, "ADMIN", stranger, "WHOLE_TREE"))
        return f
    }

    private fun readyPhoto(f: Family, label: String): String {
        val media = UUID.randomUUID()
        sql(
            "insert into media (id, tree_id, purpose, status, content_type, upload_byte_size, upload_key, storage_key, byte_size, width, height, sha256, uploaded_by_account_id, finalized_at) " +
                "values (?, ?, 'PROFILE_PHOTO', 'READY', 'image/png', 1, ?, ?, 1, 1, 1, 'x', ?, now())",
            media, UUID.fromString(f.tree), "uploads/${f.tree}/$media", "media/${f.tree}/$media/x", UUID.fromString(Actor.OWNER.id.value),
        )
        sql("update person set profile_media_id = ? where tree_id = ? and id = ?", media, UUID.fromString(f.tree), UUID.fromString(f.id(label)))
        return media.toString()
    }

    private fun sql(statement: String, vararg args: Any?) = dataSource.connection.use { c ->
        c.prepareStatement(statement).use { st ->
            args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeUpdate()
        }
    }

    /** Presigns downloads only; the routes under test never upload or read bytes. */
    private object DownloadOnlyStore : ObjectStore {
        override suspend fun presignUpload(key: String, contentType: String, byteSize: Long, ttl: Duration): PresignedUpload = error("not used")
        override suspend fun presignDownload(key: String, contentType: String, ttl: Duration) =
            PresignedDownload("https://store.test/$key", Instant.now().plus(ttl))
        override suspend fun read(key: String, maxBytes: Long): StoredBytes = error("not used")
        override suspend fun write(key: String, bytes: ByteArray, contentType: String) = error("not used")
        override suspend fun delete(key: String) = error("not used")
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(StatusPages) { configureErrorMapping() }
            install(Authentication) {
                jwt(OIDC_AUTH_NAME) {
                    verifier(JWT.require(Algorithm.HMAC256(TEST_SECRET)).build())
                    validate { JWTPrincipal(it.payload) }
                }
            }
            routing {
                personRoutes()
                graphRoutes()
                unionRoutes()
                eventRoutes()
                citationRoutes()
                mediaRoutes(MediaService(lazyOf(DownloadOnlyStore)))
                // Installed directly here: in the application they sit behind the invitation gate.
                grantRoutes()
            }
        }
        block()
    }

    private suspend fun HttpClient.send(method: HttpMethod, path: String, actor: Actor, body: String? = null): HttpResponse =
        request(path) {
            this.method = method
            bearerAuth(token(actor))
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> HttpClient.get(path: String, actor: Actor): T {
        val response = send(HttpMethod.Get, path, actor)
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, "$actor GET $path: $body")
        return json.decodeFromString(body)
    }

    private suspend inline fun <reified T> HttpResponse.decoded(status: HttpStatusCode = HttpStatusCode.OK): T {
        val body = bodyAsText()
        assertEquals(status, this.status, body)
        return json.decodeFromString(body)
    }

    /** Asserts [dto] is shown in full or as the placeholder, by what [actor] may see. */
    private fun assertShape(f: Family, actor: Actor, dto: PersonDto, context: String) {
        val label = f.label(dto.id)
        val visible = label in DECEASED || label in VISIBLE_LIVING.getValue(actor)
        val where = "$actor $context $label"
        if (visible) {
            assertFalse(dto.redacted, where)
            assertEquals(label, dto.givenNames, where)
            assertEquals("Varela", dto.surnamePaternal, where)
            assertEquals("bio of $label", dto.bio, where)
            assertEquals("FEMALE", dto.sex, where)
            assertEquals(f.photos[label], dto.profileMediaId, where)
        } else {
            assertTrue(dto.redacted, where)
            assertEquals("Private", dto.givenNames, where)
            assertEquals("Private", dto.fullName, where)
            assertNull(dto.surnamePaternal, where)
            assertNull(dto.surnameMaternal, where)
            assertNull(dto.bio, where)
            assertNull(dto.profileMediaId, where)
            assertEquals("UNKNOWN", dto.sex, where)
            assertTrue(dto.isLiving, where)
        }
    }

    private fun sees(actor: Actor, label: String) = label in DECEASED || label in VISIBLE_LIVING.getValue(actor)

    // --- People: list, detail, whole tree, ancestors, descendants ---------------------

    @Test
    fun `every person route shows exactly the living people a reader's grants cover`() = api {
        val f = newFamily()
        for (actor in VISIBLE_LIVING.keys) {
            val listed = client.get<List<PersonDto>>("${f.path}/people", actor)
            assertEquals(LABELS.toSet(), listed.map { f.label(it.id) }.toSet(), "$actor list keeps everyone")
            listed.forEach { assertShape(f, actor, it, "list") }

            val graph = client.get<TreeGraphDto>("${f.path}/graph", actor)
            assertEquals(LABELS.toSet(), graph.people.map { f.label(it.id) }.toSet(), "$actor graph keeps everyone")
            graph.people.forEach { assertShape(f, actor, it, "graph") }

            for (label in LABELS) assertShape(f, actor, client.get("${f.path}/people/${f.id(label)}", actor), "detail")

            val ancestors = client.get<List<PersonDto>>("${f.path}/people/${f.id("me")}/ancestors", actor)
            assertEquals(setOf("mum", "dad", "gp1", "gp2"), ancestors.map { f.label(it.id) }.toSet(), "$actor ancestors")
            ancestors.forEach { assertShape(f, actor, it, "ancestors") }

            val descendants = client.get<List<PersonDto>>("${f.path}/people/${f.id("gp1")}/descendants", actor)
            assertEquals(setOf("mum", "aunt", "me", "sib", "cousin"), descendants.map { f.label(it.id) }.toSet(), "$actor descendants")
            descendants.forEach { assertShape(f, actor, it, "descendants") }
        }
    }

    @Test
    fun `the home branch alone reveals no one, not even the member's own person`() = api {
        val f = newFamily()
        // VIEWER is linked to `me`; their home branch is mum, dad, me and sib.
        for (label in listOf("me", "mum", "dad", "sib")) {
            assertTrue(client.get<PersonDto>("${f.path}/people/${f.id(label)}", Actor.VIEWER).redacted, label)
        }
    }

    @Test
    fun `non-readers get the usual refusals and another tree's grant counts for nothing`() = api {
        val f = newFamily()
        for (actor in listOf(Actor.INVITEE, Actor.OUTSIDER)) {
            for (path in listOf("people", "graph", "unions", "people/${f.id("mum")}", "people/${f.id("mum")}/events")) {
                assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Get, "${f.path}/$path", actor).status, "$actor $path")
            }
        }
    }

    // --- Unions, events, citations, media ---------------------------------------------

    @Test
    fun `unions keep type and links for everyone, and dates only when both partners are visible`() = api {
        val f = newFamily()
        val expectedYears = mapOf("G" to "1940", "P" to "1975", "A" to "1970", "O" to "1990")
        val partners = mapOf("G" to ("gp1" to "gp2"), "P" to ("mum" to "dad"), "A" to ("aunt" to "uncle"), "O" to ("out1" to "out2"))
        for (actor in VISIBLE_LIVING.keys) {
            val fromList = client.get<List<UnionDto>>("${f.path}/unions", actor).associateBy { it.id }
            val fromGraph = client.get<TreeGraphDto>("${f.path}/graph", actor).unions.associateBy { it.id }
            for ((label, pair) in partners) {
                for (union in listOf(fromList.getValue(f.union(label)), fromGraph.getValue(f.union(label)))) {
                    assertEquals("MARRIED", union.type, "$actor $label")
                    assertEquals(f.id(pair.first), union.partner1Id, "$actor $label")
                    assertEquals(f.id(pair.second), union.partner2Id, "$actor $label")
                    val bothVisible = sees(actor, pair.first) && sees(actor, pair.second)
                    assertEquals(if (bothVisible) expectedYears.getValue(label) else null, union.startDate?.displayText, "$actor $label dates")
                }
            }
            assertEquals(listOf(f.id("me"), f.id("sib")).sorted(), fromList.getValue(f.union("P")).children.map { it.childId }.sorted(), "$actor links")
        }
    }

    @Test
    fun `events are read only for people the reader sees, without revealing the hidden ones`() = api {
        val f = newFamily()
        for (actor in VISIBLE_LIVING.keys) {
            for (label in LABELS) {
                val events = client.get<List<EventDto>>("${f.path}/people/${f.id(label)}/events", actor)
                if (sees(actor, label)) {
                    assertEquals("born $label", events.single().notes, "$actor $label")
                } else {
                    assertEquals(emptyList(), events, "$actor $label")
                }
            }
        }
    }

    @Test
    fun `citations follow every subject's visibility, and a hidden event reads as missing`() = api {
        val f = newFamily()
        for (actor in VISIBLE_LIVING.keys) {
            for (label in listOf("mum", "cousin", "gp1")) {
                val onPerson = client.get<List<CitationDto>>("${f.path}/people/${f.id(label)}/citations", actor)
                val eventPath = "${f.path}/events/${f.events.getValue(label)}/citations"
                if (sees(actor, label)) {
                    assertEquals("p. ${f.id(label)}", onPerson.single().page, "$actor $label")
                    assertEquals(1, client.get<List<CitationDto>>(eventPath, actor).size, "$actor $label event")
                } else {
                    assertEquals(emptyList(), onPerson, "$actor $label")
                    assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, eventPath, actor).status, "$actor $label event")
                }
            }
            for ((label, pair) in mapOf("P" to ("mum" to "dad"), "A" to ("aunt" to "uncle"))) {
                val onUnion = client.get<List<CitationDto>>("${f.path}/unions/${f.union(label)}/citations", actor)
                assertEquals(if (sees(actor, pair.first) && sees(actor, pair.second)) 1 else 0, onUnion.size, "$actor $label")
            }
        }
    }

    @Test
    fun `profile photos download only for readers who see the person`() = api {
        val f = newFamily()
        for (actor in VISIBLE_LIVING.keys) {
            for ((label, media) in f.photos) {
                val response = client.send(HttpMethod.Get, "${f.path}/media/$media/download", actor)
                if (sees(actor, label)) {
                    assertEquals(media, response.decoded<MediaDownloadDto>().mediaId, "$actor $label")
                } else {
                    assertEquals(HttpStatusCode.NotFound, response.status, "$actor $label")
                }
            }
        }
        // An unattached image stays the owner's only, whatever grant: even a whole-tree admin.
        sql("update person set profile_media_id = null where tree_id = ? and id = ?", UUID.fromString(f.tree), UUID.fromString(f.id("mum")))
        val unattached = f.photos.getValue("mum")
        assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "${f.path}/media/$unattached/download", Actor.OWNER).status)
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "${f.path}/media/$unattached/download", Actor.ADMIN).status)
    }

    // --- Changes to grants and the graph ----------------------------------------------

    @Test
    fun `a revoked grant hides at once, and people added inside a grant's branch show at once`() = api {
        val f = newFamily()
        assertFalse(client.get<PersonDto>("${f.path}/people/${f.id("cousin")}", Actor.VIEWER).redacted)
        // A stored, current cache (the write-capable refresh path) must not outlive the next change.
        val viewerGrant = f.grants.getValue(Actor.VIEWER)
        assertEquals(
            setOf(f.id("aunt"), f.id("cousin")),
            GrantScopeService.refreshScope(f.treeUuid, Uuid.parse(viewerGrant.value)).mapTo(mutableSetOf()) { it.toString() },
        )

        // A new living child of cousin joins the VIEWER's descendant branch (cache stamps, FT-010).
        val baby = PersonService.createPerson(f.treeId, Actor.OWNER.id, CreatePersonRequest("baby", "Varela", sex = "FEMALE", isLiving = true)).first.id.value
        assertTrue(client.get<PersonDto>("${f.path}/people/$baby", Actor.VIEWER).redacted, "not in the branch yet")
        val union = UnionService.createUnion(f.treeId, Actor.OWNER.id, CreateUnionRequest(f.id("cousin"), null, "MARRIED"))
        UnionService.addChild(f.treeId, UnionId(union.id), Actor.OWNER.id, AddUnionChildRequest(baby, "BIRTH"))
        assertEquals("baby", client.get<PersonDto>("${f.path}/people/$baby", Actor.VIEWER).givenNames)

        val photo = "${f.path}/media/${f.photos.getValue("cousin")}/download"
        assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, photo, Actor.VIEWER).status)

        // Revoked through the grant API, as an owner does it.
        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "${f.path}/grants/${viewerGrant.value}?version=1", Actor.OWNER).status)
        for (label in listOf("cousin", "aunt")) {
            assertTrue(client.get<PersonDto>("${f.path}/people/${f.id(label)}", Actor.VIEWER).redacted, label)
        }
        assertTrue(client.get<PersonDto>("${f.path}/people/$baby", Actor.VIEWER).redacted)
        assertEquals(emptyList(), client.get<List<EventDto>>("${f.path}/people/${f.id("cousin")}/events", Actor.VIEWER))
        assertEquals(emptyList(), client.get<List<CitationDto>>("${f.path}/people/${f.id("cousin")}/citations", Actor.VIEWER))
        // No new download link after the revocation (one already issued lives out its 5 minutes).
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, photo, Actor.VIEWER).status)
        // A deceased person stays visible with no grant at all.
        assertEquals("uncle", client.get<PersonDto>("${f.path}/people/${f.id("uncle")}", Actor.VIEWER).givenNames)
    }

    @Test
    fun `snapshot scopes need the read transaction's isolation`() {
        val f = runBlocking { newFamily() }
        assertFailsWith<IllegalStateException> {
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
                GrantScopeService.scopedGrantsInSnapshot(f.treeUuid, Actor.VIEWER.uuid)
            }
        }
        val grants = transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) {
            GrantScopeService.scopedGrantsInSnapshot(f.treeUuid, Actor.LIMITED_ADMIN.uuid)
        }
        assertEquals(emptyList(), grants, "a limited ADMIN grant isn't even resolved")
    }

    @Test
    fun `ancestor and descendant reads share the viewer snapshot`() = runBlocking {
        val f = newFamily()
        val mother = Uuid.parse(f.id("mum"))
        val child = Uuid.parse(f.id("me"))
        val grandparent = Uuid.parse(f.id("gp1"))
        ReadAccess.read(f.treeId, Actor.CONTRIBUTOR.id) { _, viewer ->
            assertEquals(1, sql("update person set given_names = ? where tree_id = ? and id = ?", "Changed", UUID.fromString(f.tree), UUID.fromString(f.id("mum"))))
            val ancestors = PersonRepository.ancestorsOf(child, f.treeUuid)
            val descendants = PersonRepository.descendantsOf(grandparent, f.treeUuid)
            for (people in listOf(ancestors, descendants)) {
                assertEquals("mum", viewer.project(people.single { it.id.value == mother.toString() }).givenNames)
            }
        }
        assertEquals("Changed", PersonRepository.findById(f.treeUuid, mother)?.givenNames)
    }

    // --- Writes -------------------------------------------------------------------------

    @Test
    fun `write responses follow the writer's grants`() = api {
        val f = newFamily()
        // dad: inside the CONTRIBUTOR's grant (a partner of mum), so the response is in full.
        val dad = client.get<PersonDto>("${f.path}/people/${f.id("dad")}", Actor.OWNER)
        val updated = client.send(
            HttpMethod.Put, "${f.path}/people/${f.id("dad")}", Actor.CONTRIBUTOR,
            encode(UpdatePersonRequest("dad", "Varela", sex = "FEMALE", isLiving = true, bio = "new bio", version = dad.version)),
        ).decoded<PersonDto>()
        assertFalse(updated.redacted)
        assertEquals("new bio", updated.bio)

        // A union the contributor adds between two people they see keeps its dates.
        val union = client.send(
            HttpMethod.Post, "${f.path}/unions", Actor.CONTRIBUTOR,
            encode(CreateUnionRequest(f.id("me"), null, "PARTNERS", startDate = "2010")),
        ).decoded<UnionDto>(HttpStatusCode.Created)
        assertEquals("2010", union.startDate?.displayText)
    }

    @Test
    fun `duplicate candidates are scored only against people the creator sees`() = api {
        val f = newFamily()
        suspend fun candidates(name: String) = client.send(
            HttpMethod.Post, "${f.path}/people", Actor.CONTRIBUTOR,
            encode(CreatePersonRequest(name, "Varela", sex = "FEMALE", isLiving = false)),
        ).decoded<CreatePersonResponse>(HttpStatusCode.Created).possibleDuplicates.map { it.person }
        // cousin is living and outside the contributor's grant: no candidate, placeholder or score.
        assertTrue(candidates("cousin").none { it.id == f.id("cousin") })
        // mum is covered: offered in full.
        val mum = candidates("mum").single { it.id == f.id("mum") }
        assertEquals("mum", mum.givenNames)
        assertFalse(mum.redacted)
    }

    @Test
    fun `an event the writer can't see stays missing, one they see but can't edit is refused`() = api {
        val f = newFamily()
        fun update(label: String) = encode(UpdateEventRequest(personId = f.id(label), type = "BIRTH", version = 1))
        // cousin: living, outside the contributor's grant and hidden: the missing-event 404.
        assertEquals(
            HttpStatusCode.NotFound,
            client.send(HttpMethod.Put, "${f.path}/events/${f.events.getValue("cousin")}", Actor.CONTRIBUTOR, update("cousin")).status,
        )
        // gp1: deceased, so visible, but outside their branches: 403.
        assertEquals(
            HttpStatusCode.Forbidden,
            client.send(HttpMethod.Put, "${f.path}/events/${f.events.getValue("gp1")}", Actor.CONTRIBUTOR, update("gp1")).status,
        )
        // mum: covered and editable; the update goes through.
        assertNotNull(client.send(HttpMethod.Put, "${f.path}/events/${f.events.getValue("mum")}", Actor.CONTRIBUTOR, update("mum")).decoded<EventDto>())
    }

    // --- One partner revealed, the other not ------------------------------------------

    @Test
    fun `a VIEWER grant reveals one partner while the other stays private, until a second grant, and revoking it restores the first scope`() = api {
        val f = newFamily()
        val unionCitations = "${f.path}/unions/${f.union("O")}/citations"
        val eventCitations = "${f.path}/events/${f.events.getValue("union O")}/citations"
        suspend fun person(label: String) = client.get<PersonDto>("${f.path}/people/${f.id(label)}", Actor.PAIR_VIEWER)
        suspend fun unions() = listOf(
            client.get<List<UnionDto>>("${f.path}/unions", Actor.PAIR_VIEWER).single { it.id == f.union("O") },
            client.get<TreeGraphDto>("${f.path}/graph", Actor.PAIR_VIEWER).unions.single { it.id == f.union("O") },
        )
        suspend fun assertOnlyOut1() {
            assertEquals("out1", person("out1").givenNames)
            assertTrue(person("out2").redacted)
            for (union in unions()) {
                assertEquals("MARRIED", union.type)
                assertEquals(f.id("out1") to f.id("out2"), union.partner1Id to union.partner2Id)
                assertEquals(listOf(f.id("outKid")), union.children.map { it.childId })
                assertNull(union.startDate, "dates need both partners")
            }
            assertEquals(emptyList(), client.get<List<CitationDto>>(unionCitations, Actor.PAIR_VIEWER))
            assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, eventCitations, Actor.PAIR_VIEWER).status)
        }
        assertOnlyOut1()

        // A second explicit grant, through the API, covers out2: the union opens up.
        val second = client.send(
            HttpMethod.Post, "${f.path}/grants", Actor.OWNER,
            encode(CreateGrantRequest(Actor.PAIR_VIEWER.id.value, "VIEWER", f.id("out2"), "ANCESTORS")),
        ).decoded<GrantDto>(HttpStatusCode.Created)
        assertEquals("out2", person("out2").givenNames)
        for (union in unions()) assertEquals("1990", union.startDate?.displayText)
        assertEquals(1, client.get<List<CitationDto>>(unionCitations, Actor.PAIR_VIEWER).size)
        assertEquals(1, client.get<List<CitationDto>>(eventCitations, Actor.PAIR_VIEWER).size)

        // Revoking it leaves exactly the first grant's scope.
        assertEquals(
            HttpStatusCode.NoContent,
            client.send(HttpMethod.Delete, "${f.path}/grants/${second.id}?version=${second.version}", Actor.OWNER).status,
        )
        assertOnlyOut1()
    }

    // --- Writes that would reveal what the writer can't see ---------------------------

    /** Everything the denied writes below could change, in [tree]. */
    private fun fingerprint(tree: String): List<String?> = listOf(
        "select count(*) from union_record where tree_id = ?",
        "select coalesce(sum(version), 0) from union_record where tree_id = ?",
        "select count(*) from union_child where tree_id = ?",
        "select coalesce(sum(version), 0) from person where tree_id = ?",
        "select string_agg(id::text || is_living::text, ',' order by id) from person where tree_id = ?",
        "select coalesce(sum(version), 0) from event where tree_id = ?",
        "select count(*) from audit_log where tree_id = ?",
        "select generation from tree_graph_generation where tree_id = ?",
    ).map { query(it, UUID.fromString(tree)) }

    private fun query(statement: String, vararg args: Any?): String? = dataSource.connection.use { c ->
        c.prepareStatement(statement).use { st ->
            args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    private suspend fun HttpResponse.expectRefusal(f: Family, context: String) {
        val body = bodyAsText()
        assertEquals(HttpStatusCode.Forbidden, status, "$context: $body")
        assertEquals(WriteAccess.revealsPrivateMessage(f.tree), json.decodeFromString<ApiErrorResponse>(body).message, context)
    }

    /** Asserts [block] is refused as revealing private details and changes nothing, in this tree or the other one. */
    private suspend fun refusedWithNoChange(f: Family, context: String, block: suspend () -> HttpResponse) {
        val before = fingerprint(f.tree) to fingerprint(f.otherTree)
        block().expectRefusal(f, context)
        assertEquals(before, fingerprint(f.tree) to fingerprint(f.otherTree), "$context wrote nothing")
    }

    @Test
    fun `a union that would reveal a hidden living partner through a VIEWER grant is refused with nothing written`() = api {
        val f = newFamily()
        // REACHER edits out1 (CONTRIBUTOR, no partners) and sees out1's partners (VIEWER):
        // a union with aunt adds no edit reach, but would reveal aunt.
        assertTrue(client.get<PersonDto>("${f.path}/people/${f.id("aunt")}", Actor.REACHER).redacted)
        refusedWithNoChange(f, "union out1 + aunt") {
            client.send(
                HttpMethod.Post, "${f.path}/unions", Actor.REACHER,
                encode(CreateUnionRequest(f.id("out1"), f.id("aunt"), "MARRIED", startDate = "2000")),
            )
        }
        assertTrue(client.get<PersonDto>("${f.path}/people/${f.id("aunt")}", Actor.REACHER).redacted)
        assertTrue(
            client.get<List<UnionDto>>("${f.path}/unions", Actor.OWNER).none { f.id("aunt") in listOf(it.partner1Id, it.partner2Id) && f.id("out1") in listOf(it.partner1Id, it.partner2Id) },
        )
        // Swapping a partner in is refused the same way.
        val o = client.get<List<UnionDto>>("${f.path}/unions", Actor.OWNER).single { it.id == f.union("O") }
        refusedWithNoChange(f, "union O with a second partner aunt") {
            client.send(
                HttpMethod.Put, "${f.path}/unions/${o.id}", Actor.REACHER,
                encode(UpdateUnionRequest(f.id("out1"), f.id("aunt"), "MARRIED", version = o.version)),
            )
        }
    }

    @Test
    fun `the writer's own new person can still be created and then attached`() = api {
        val f = newFamily()
        val created = client.send(
            HttpMethod.Post, "${f.path}/people", Actor.REACHER,
            encode(CreatePersonRequest("newbie", "Varela", sex = "FEMALE", isLiving = true)),
        ).decoded<CreatePersonResponse>(HttpStatusCode.Created).person
        assertTrue(client.get<PersonDto>("${f.path}/people/${created.id}", Actor.REACHER).redacted, "no grant covers it yet")

        val attached = client.send(
            HttpMethod.Post, "${f.path}/unions/${f.union("O")}/children", Actor.REACHER,
            encode(AddUnionChildRequest(created.id, "BIRTH")),
        )
        assertTrue(attached.status.isSuccess(), attached.bodyAsText())
        assertEquals("newbie", client.get<PersonDto>("${f.path}/people/${created.id}", Actor.REACHER).givenNames)
    }

    @Test
    fun `a writer who can't see a living person can't mark them deceased`() = api {
        val f = newFamily()
        // HOME_ONLY edits aunt live through their home branch, but no grant reveals her.
        val aunt = client.get<PersonDto>("${f.path}/people/${f.id("aunt")}", Actor.OWNER)
        refusedWithNoChange(f, "aunt marked deceased") {
            client.send(
                HttpMethod.Put, "${f.path}/people/${aunt.id}", Actor.HOME_ONLY,
                encode(UpdatePersonRequest("aunt", "Varela", sex = "FEMALE", isLiving = false, bio = "bio of aunt", version = aunt.version)),
            )
        }
        assertTrue(client.get<PersonDto>("${f.path}/people/${aunt.id}", Actor.NO_GRANT).redacted)
        assertEquals(emptyList(), client.get<List<EventDto>>("${f.path}/people/${aunt.id}/events", Actor.NO_GRANT))
        // The same writer still edits her otherwise, and gets the placeholder back.
        val edited = client.send(
            HttpMethod.Put, "${f.path}/people/${aunt.id}", Actor.HOME_ONLY,
            encode(UpdatePersonRequest("aunt", "Varela", sex = "FEMALE", isLiving = true, bio = "new bio", version = aunt.version)),
        ).decoded<PersonDto>()
        assertTrue(edited.redacted)

        // An editor who sees the person (dad, inside the CONTRIBUTOR's grant) marks them deceased as usual.
        val dad = client.get<PersonDto>("${f.path}/people/${f.id("dad")}", Actor.OWNER)
        val deceased = client.send(
            HttpMethod.Put, "${f.path}/people/${dad.id}", Actor.CONTRIBUTOR,
            encode(UpdatePersonRequest("dad", "Varela", sex = "FEMALE", isLiving = false, bio = "bio of dad", version = dad.version)),
        ).decoded<PersonDto>()
        assertFalse(deceased.isLiving)
        assertEquals("dad", client.get<PersonDto>("${f.path}/people/${dad.id}", Actor.NO_GRANT).givenNames)
    }

    @Test
    fun `a union can't drop a hidden partner, while changes keeping its people are projected as usual`() = api {
        val f = newFamily()
        val a = client.get<List<UnionDto>>("${f.path}/unions", Actor.OWNER).single { it.id == f.union("A") }
        // aunt is hidden from HOME_ONLY; the union's events and citations describe her.
        for (replacement in listOf(f.id("gp2") to f.id("uncle"), f.id("uncle") to null)) {
            refusedWithNoChange(f, "union A as $replacement") {
                client.send(
                    HttpMethod.Put, "${f.path}/unions/${a.id}", Actor.HOME_ONLY,
                    encode(UpdateUnionRequest(replacement.first, replacement.second, "MARRIED", startDate = "1970", version = a.version)),
                )
            }
        }
        val kept = client.send(
            HttpMethod.Put, "${f.path}/unions/${a.id}", Actor.HOME_ONLY,
            encode(UpdateUnionRequest(f.id("uncle"), f.id("aunt"), "DIVORCED", startDate = "1971", version = a.version)),
        ).decoded<UnionDto>()
        assertEquals("DIVORCED", kept.type)
        assertEquals(setOf(f.id("uncle"), f.id("aunt")), setOf(kept.partner1Id, kept.partner2Id))
        assertEquals(listOf(f.id("cousin")), kept.children.map { it.childId })
        assertNull(kept.startDate, "aunt is hidden from the writer")
    }

    @Test
    fun `an event can't move off a hidden person`() = api {
        val f = newFamily()
        val event = "${f.path}/events/${f.events.getValue("aunt")}"
        refusedWithNoChange(f, "aunt's birth moved to uncle") {
            client.send(HttpMethod.Put, event, Actor.HOME_ONLY, encode(UpdateEventRequest(personId = f.id("uncle"), type = "BIRTH", version = 1)))
        }
        val kept = client.send(
            HttpMethod.Put, event, Actor.HOME_ONLY,
            encode(UpdateEventRequest(personId = f.id("aunt"), type = "BIRTH", date = "2 FEB 1950", version = 1)),
        ).decoded<EventDto>()
        assertEquals(f.id("aunt"), kept.personId)
    }
}
