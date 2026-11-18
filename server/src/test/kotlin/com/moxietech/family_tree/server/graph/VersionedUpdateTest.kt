package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.api.CreatePlaceRequest
import com.moxietech.family_tree.api.CreateSourceRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.PlaceDto
import com.moxietech.family_tree.api.SourceDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionChildDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.api.UpdateEventRequest
import com.moxietech.family_tree.api.UpdatePersonRequest
import com.moxietech.family_tree.api.UpdatePlaceRequest
import com.moxietech.family_tree.api.UpdateSourceRequest
import com.moxietech.family_tree.api.UpdateUnionChildRequest
import com.moxietech.family_tree.api.UpdateUnionRequest
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.EventId
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.PlaceId
import com.moxietech.family_tree.domain.SourceId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.module
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.config.ApiErrorResponse
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DEFAULT_API_AUDIENCE
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.config.MALFORMED_BODY_MESSAGE
import com.moxietech.family_tree.server.config.OidcSettings
import com.moxietech.family_tree.server.config.TestJwks
import com.moxietech.family_tree.server.identity.IdentityTestApi.bodyWithStatus
import com.moxietech.family_tree.server.identity.IdentityTestApi.createTree
import com.moxietech.family_tree.server.identity.IdentityTestApi.me
import com.moxietech.family_tree.server.identity.IdentityTestApi.query
import com.moxietech.family_tree.server.identity.IdentityTestApi.uuid
import com.moxietech.family_tree.server.sources.SourceService
import com.moxietech.family_tree.server.trees.MembershipRepository
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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.postgresql.util.PSQLException
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * FT-001 versioned updates over HTTP, through the production [module] and
 * verifier (as in `AuthenticatedAccessTest`), on a disposable PostgreSQL 16
 * container migrated by Flyway.
 *
 * Actors: owner owns tree A; other owner owns tree B; relative is an ACTIVE
 * non-owner member of tree A; invitee holds only an INVITED membership in A.
 *
 * Most tests build a fresh [Family] so their versions and audit entries don't
 * depend on test order. "Nothing changed" is checked against a fingerprint of
 * every row (not just counts), because a wrongly applied update keeps counts.
 */
class VersionedUpdateTest {

    private enum class Actor(val subject: String, val email: String) {
        OWNER("ft001-owner", "ft001-owner@example.com"),
        OTHER_OWNER("ft001-other-owner", "ft001-other-owner@example.com"),
        RELATIVE("ft001-relative", "ft001-relative@example.com"),
        INVITEE("ft001-invitee", "ft001-invitee@example.com"),
    }

    /** Two partners, their union with one child link, a place, a source and the father's birth at the place. */
    private data class Family(
        val tree: String,
        val father: PersonDto,
        val mother: PersonDto,
        val child: PersonDto,
        /** As returned after the child link was added. */
        val union: UnionDto,
        val place: PlaceDto,
        val source: SourceDto,
        val event: EventDto,
    ) {
        val childLink: UnionChildDto get() = union.children.single { it.childId == child.id }
    }

    /**
     * One update route applied to one record of a [Family], with a body that
     * succeeds for the tree's owner at the record's current [version].
     */
    private data class Update(
        val name: String,
        val entityType: String,
        val entityId: String,
        /** How the 409 message names the record. */
        val conflictLabel: String,
        val path: String,
        val body: (version: Long) -> String,
        val version: Long,
        /** The audit "before" snapshot an update from [version] must record. */
        val before: JsonElement,
        /** Reads the updated record's version from a successful response. */
        val versionIn: (JsonElement) -> Long,
        val table: String,
        val keyWhere: String,
        val keyArgs: List<UUID>,
    ) {
        val conflict get() = "$conflictLabel $entityId has been changed since you read it; reload it and try again"
    }

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val ISSUER = "http://keycloak.test/realms/family-tree"
        private const val TRIGGER_MESSAGE = "FT-001 test-only trigger rejected the update audit"
        private val json = Json { ignoreUnknownKeys = true }
        private val tables = listOf("person", "union_record", "union_child", "event", "place", "source", "membership", "audit_log")

        private lateinit var dataSource: HikariDataSource
        private lateinit var jwks: TestJwks
        private lateinit var accounts: Map<Actor, AccountDto>
        private lateinit var treeA: String
        private lateinit var treeB: String

        /** Tree B's family: the foreign rows for the tenant-isolation cases. */
        private lateinit var foreign: Family

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dataSource = DatabaseConfig.createDataSource(
                DatabaseSettings(jdbcUrl = postgres.jdbcUrl, user = postgres.username, password = postgres.password),
            )
            DatabaseConfig.migrate(dataSource)
            DatabaseConfig.connectExposed(dataSource)
            AppDatabase.init(dataSource)
            jwks = TestJwks()
            testApplication {
                application { module(settings()) }
                accounts = Actor.entries.associateWith { client.me(token(it)) }
                treeA = client.createTree(token(Actor.OWNER), "FT-001 A").id
                treeB = client.createTree(token(Actor.OTHER_OWNER), "FT-001 B").id
                // No invitation route yet, so the non-owner memberships are inserted directly.
                MembershipRepository.insert(
                    Uuid.parse(treeA), Uuid.parse(accounts.getValue(Actor.RELATIVE).id), personId = null, status = MembershipStatus.ACTIVE,
                )
                MembershipRepository.insert(
                    Uuid.parse(treeA), Uuid.parse(accounts.getValue(Actor.INVITEE).id), personId = null, status = MembershipStatus.INVITED,
                )
                foreign = client.newFamily(treeB, Actor.OTHER_OWNER, surname = "Quispe", placeName = "Lima")
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::jwks.isInitialized) jwks.close()
            if (::dataSource.isInitialized) dataSource.close()
        }

        private fun settings() = OidcSettings(issuer = ISSUER, jwksUri = jwks.jwksUri, audience = DEFAULT_API_AUDIENCE)

        private fun token(actor: Actor): String = jwks.accessToken(ISSUER, email = actor.email) { withSubject(actor.subject) }

        private fun path(treeId: String) = "/api/v1/trees/$treeId"

        private inline fun <reified T> encode(value: T): String = json.encodeToString(value)

        private suspend inline fun <reified T> HttpResponse.decoded(status: HttpStatusCode = HttpStatusCode.OK): T =
            json.decodeFromString(bodyWithStatus(status))

        private suspend fun HttpClient.send(method: HttpMethod, path: String, actor: Actor, body: String): HttpResponse =
            request(path) {
                this.method = method
                bearerAuth(token(actor))
                contentType(ContentType.Application.Json)
                setBody(body)
            }

        private suspend fun HttpClient.put(path: String, actor: Actor, body: String) = send(HttpMethod.Put, path, actor, body)

        private suspend fun HttpClient.post(path: String, actor: Actor, body: String) = send(HttpMethod.Post, path, actor, body)

        private suspend fun HttpClient.newFamily(
            tree: String,
            owner: Actor,
            surname: String = "Ruiz",
            placeName: String = "Cádiz",
        ): Family {
            val t = path(tree)
            suspend fun person(given: String, sex: String) = post(
                "$t/people", owner, encode(CreatePersonRequest(givenNames = given, surnamePaternal = surname, sex = sex, isLiving = false)),
            ).decoded<CreatePersonResponse>(HttpStatusCode.Created).person
            val father = person("Mateo", "MALE")
            val mother = person("Julia", "FEMALE")
            val child = person("Ines", "FEMALE")
            val created = post("$t/unions", owner, encode(CreateUnionRequest(father.id, mother.id, type = "MARRIED", startDate = "1970")))
                .decoded<UnionDto>(HttpStatusCode.Created)
            val union = post("$t/unions/${created.id}/children", owner, encode(AddUnionChildRequest(child.id, "BIRTH")))
                .decoded<UnionDto>(HttpStatusCode.Created)
            val place = post("$t/places", owner, encode(CreatePlaceRequest(placeName))).decoded<PlaceDto>(HttpStatusCode.Created)
            val source = post("$t/sources", owner, encode(CreateSourceRequest(title = "Parish register", type = "CHURCH_RECORD")))
                .decoded<SourceDto>(HttpStatusCode.Created)
            val event = post(
                "$t/events", owner,
                encode(CreateEventRequest(personId = father.id, type = "BIRTH", date = "1945", placeId = place.id, notes = "Born at home")),
            ).decoded<EventDto>(HttpStatusCode.Created)
            return Family(tree, father, mother, child, union, place, source, event)
        }
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(settings()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.family() = client.newFamily(treeA, Actor.OWNER)

    private fun JsonElement.version(): Long = jsonObject.getValue("version").jsonPrimitive.long

    /** The child link for [childId] in a union's JSON, chosen by ID rather than position. */
    private fun childIn(union: JsonElement, childId: String): JsonElement =
        union.jsonObject.getValue("children").jsonArray.single { it.jsonObject.getValue("childId").jsonPrimitive.content == childId }

    /** Every update route, each applied to one record of [f]. */
    private fun updates(f: Family): List<Update> {
        val t = path(f.tree)
        return listOf(
            Update(
                name = "person", entityType = "Person", entityId = f.father.id, conflictLabel = "Person",
                path = "$t/people/${f.father.id}",
                body = { v ->
                    encode(
                        UpdatePersonRequest(
                            givenNames = "Mateo José", surnamePaternal = "Ruiz", surnameMaternal = "Díaz",
                            sex = "MALE", isLiving = false, bio = "Fisherman", version = v,
                        ),
                    )
                },
                version = f.father.version, before = AuditJson.encodeToJsonElement(f.father),
                versionIn = { it.version() },
                table = "person", keyWhere = "id = ?", keyArgs = listOf(uuid(f.father.id)),
            ),
            Update(
                name = "union", entityType = "Union", entityId = f.union.id, conflictLabel = "Union",
                path = "$t/unions/${f.union.id}",
                body = { v ->
                    encode(UpdateUnionRequest(f.father.id, f.mother.id, type = "DIVORCED", startDate = "1970", endDate = "1990", version = v))
                },
                version = f.union.version, before = AuditJson.encodeToJsonElement(f.union),
                versionIn = { it.version() },
                table = "union_record", keyWhere = "id = ?", keyArgs = listOf(uuid(f.union.id)),
            ),
            Update(
                name = "child link", entityType = "UnionChild", entityId = "${f.union.id}:${f.child.id}", conflictLabel = "Child link",
                path = "$t/unions/${f.union.id}/children/${f.child.id}",
                body = { v -> encode(UpdateUnionChildRequest(relation = "ADOPTED", version = v)) },
                version = f.childLink.version, before = AuditJson.encodeToJsonElement(f.union),
                versionIn = { childIn(it, f.child.id).version() },
                table = "union_child", keyWhere = "union_id = ? and child_id = ?", keyArgs = listOf(uuid(f.union.id), uuid(f.child.id)),
            ),
            Update(
                name = "event", entityType = "Event", entityId = f.event.id, conflictLabel = "Event",
                path = "$t/events/${f.event.id}",
                body = { v ->
                    encode(
                        UpdateEventRequest(
                            personId = f.mother.id, type = "RESIDENCE", date = "1975", placeId = f.place.id, notes = "Moved", version = v,
                        ),
                    )
                },
                version = f.event.version, before = AuditJson.encodeToJsonElement(f.event),
                versionIn = { it.version() },
                table = "event", keyWhere = "id = ?", keyArgs = listOf(uuid(f.event.id)),
            ),
            Update(
                name = "place", entityType = "Place", entityId = f.place.id, conflictLabel = "Place",
                path = "$t/places/${f.place.id}",
                body = { v -> encode(UpdatePlaceRequest("Sevilla", "Andalucía, España", 37.39, -5.98, version = v)) },
                version = f.place.version, before = AuditJson.encodeToJsonElement(f.place),
                versionIn = { it.version() },
                table = "place", keyWhere = "id = ?", keyArgs = listOf(uuid(f.place.id)),
            ),
            Update(
                name = "source", entityType = "Source", entityId = f.source.id, conflictLabel = "Source",
                path = "$t/sources/${f.source.id}",
                body = { v ->
                    encode(UpdateSourceRequest(title = "Parish register, copy", type = "CHURCH_RECORD", author = "San Juan", date = "1950", version = v))
                },
                version = f.source.version, before = AuditJson.encodeToJsonElement(f.source),
                versionIn = { it.version() },
                table = "source", keyWhere = "id = ?", keyArgs = listOf(uuid(f.source.id)),
            ),
        )
    }

    /**
     * Every row of every tenant table and the audit log for [treeId], as text.
     * Equal before and after a request means the request changed nothing,
     * including versions and audit entries.
     */
    private fun fingerprint(treeId: String): Map<String, String?> = tables.associateWith { table ->
        dataSource.query("select string_agg(t::text, E'\\n' order by t::text) from $table t where tree_id = ?", uuid(treeId))
    }

    private fun updateAudits(treeId: String, u: Update): List<Pair<String?, String?>> = dataSource.connection.use { c ->
        c.prepareStatement(
            "select actor_account_id, before_json, after_json from audit_log " +
                "where tree_id = ? and entity_type = ? and entity_id = ? and action = 'UPDATE'",
        ).use { st ->
            st.setObject(1, uuid(treeId))
            st.setString(2, u.entityType)
            st.setString(3, u.entityId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        assertEquals(accounts.getValue(Actor.OWNER).id, rs.getString("actor_account_id"), u.name)
                        add(rs.getString("before_json") to rs.getString("after_json"))
                    }
                }
            }
        }
    }

    /**
     * A successful update's new version: one more for records with a per-record counter;
     * for child links (V5) a fresh sequence value, opaque but always larger.
     */
    private fun assertAdvanced(u: Update, newVersion: Long) {
        if (u.entityType == "UnionChild") {
            assertTrue(newVersion > u.version, "${u.name}: $newVersion should be above ${u.version}")
        } else {
            assertEquals(u.version + 1, newVersion, u.name)
        }
    }

    private fun storedVersion(u: Update): Long =
        dataSource.query("select version from ${u.table} where ${u.keyWhere}", *u.keyArgs.toTypedArray())!!.toLong()

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, message: String, context: String) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, "$context: $body")
        assertEquals(message, json.decodeFromString<ApiErrorResponse>(body).message, context)
        assertFalse("Exception" in body || "SQL" in body, "$context exposed internals: $body")
    }

    // --- Successful updates -----------------------------------------------------------

    @Test
    fun `the owner's update changes the record, bumps its version and writes one before-after audit`() = api {
        // A fresh family per route, so each audit "before" is exactly the record as created.
        for (index in updates(foreign).indices) {
            val f = family()
            val u = updates(f)[index]
            val response = client.put(u.path, Actor.OWNER, u.body(u.version))
            val body = json.parseToJsonElement(response.bodyWithStatus(HttpStatusCode.OK))
            assertAdvanced(u, u.versionIn(body))
            assertEquals(u.versionIn(body), storedVersion(u), u.name)
            assertNotEquals(u.before, body, u.name)

            val (before, after) = updateAudits(f.tree, u).single()
            assertEquals(u.before, json.parseToJsonElement(before!!), "${u.name} audit before")
            assertEquals(body, json.parseToJsonElement(after!!), "${u.name} audit after")
        }
    }

    @Test
    fun `updates are visible through the existing read routes`() = api {
        val f = family()
        for (u in updates(f)) client.put(u.path, Actor.OWNER, u.body(u.version)).bodyWithStatus(HttpStatusCode.OK)
        val t = path(f.tree)
        val person = client.request("$t/people/${f.father.id}") { bearerAuth(token(Actor.OWNER)) }.decoded<PersonDto>()
        assertEquals("Mateo José Ruiz Díaz", person.fullName)
        assertEquals(2, person.version)
        val graph = client.request("$t/graph") { bearerAuth(token(Actor.OWNER)) }.decoded<TreeGraphDto>()
        val union = graph.unions.single { it.id == f.union.id }
        assertEquals("DIVORCED", union.type)
        assertEquals(2, union.version)
        val link = union.children.single { it.childId == f.child.id }
        assertEquals("ADOPTED", link.relation)
        assertEquals(storedVersion(updates(f).single { it.name == "child link" }), link.version)
        assertTrue(link.version > f.childLink.version)
        val events = client.request("$t/people/${f.mother.id}/events") { bearerAuth(token(Actor.OWNER)) }.decoded<List<EventDto>>()
        val event = events.single { it.id == f.event.id }
        assertEquals(2, event.version)
        assertEquals(PlaceDto(f.place.id, "Sevilla", "Andalucía, España", 37.39, -5.98, version = 2), event.place)
        val places = client.request("$t/places") { bearerAuth(token(Actor.OWNER)) }.decoded<List<PlaceDto>>()
        assertEquals(2, places.single { it.id == f.place.id }.version)
        val sources = client.request("$t/sources") { bearerAuth(token(Actor.OWNER)) }.decoded<List<SourceDto>>()
        assertEquals("Parish register, copy", sources.single { it.id == f.source.id }.title)
    }

    @Test
    fun `create and read responses carry place and child link versions, and create bodies need none`() = api {
        val f = family()
        val t = path(f.tree)
        assertEquals(1, f.place.version)
        assertTrue(f.childLink.version >= 1)
        // The raw wire JSON carries the versions even at their default value.
        fun JsonElement.withId(id: String) = jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == id }
        val rawPlaces = json.parseToJsonElement(client.request("$t/places") { bearerAuth(token(Actor.OWNER)) }.bodyAsText())
        assertEquals(1, rawPlaces.withId(f.place.id).version())
        val rawUnions = json.parseToJsonElement(client.request("$t/unions") { bearerAuth(token(Actor.OWNER)) }.bodyAsText())
        assertEquals(f.childLink.version, childIn(rawUnions.withId(f.union.id), f.child.id).version())
        // Create bodies shaped like an existing client's (no version) are still accepted.
        val huelva = client.post("$t/places", Actor.OWNER, """{"name":"Huelva"}""").decoded<PlaceDto>(HttpStatusCode.Created)
        assertEquals(1, huelva.version)
        val sibling = client.post("$t/people", Actor.OWNER, """{"givenNames":"Pablo","sex":"MALE","isLiving":false}""")
            .decoded<CreatePersonResponse>(HttpStatusCode.Created).person
        val link = client.post("$t/unions/${f.union.id}/children", Actor.OWNER, """{"childId":"${sibling.id}","relation":"STEP"}""")
            .decoded<UnionDto>(HttpStatusCode.Created)
        // Child-link versions are sequence values (V5): read them, don't assume 1.
        val versions = link.children.map { it.version }
        assertEquals(2, versions.toSet().size, "each link has its own version: $versions")
        assertTrue(link.children.single { it.childId == sibling.id }.version > f.childLink.version)
    }

    // --- Optimistic concurrency ---------------------------------------------------------

    @Test
    fun `a stale or unknown version is a 409 that changes neither the record nor the audit log`() = api {
        val f = family()
        val all = updates(f)
        for (u in all) client.put(u.path, Actor.OWNER, u.body(u.version)).bodyWithStatus(HttpStatusCode.OK)
        val before = fingerprint(f.tree)
        for (u in all) {
            assertError(client.put(u.path, Actor.OWNER, u.body(u.version)), HttpStatusCode.Conflict, u.conflict, "${u.name} stale")
            assertError(client.put(u.path, Actor.OWNER, u.body(u.version + 1_000_000)), HttpStatusCode.Conflict, u.conflict, "${u.name} future")
        }
        assertEquals(before, fingerprint(f.tree))
    }

    /**
     * Both writers read the same version and pass the early check, then park on a
     * row lock held by the test, so they reach the conditional UPDATE together.
     * When the lock is released one commits; the other's UPDATE re-reads the row,
     * no longer matches the version and must fail with 409, auditing nothing.
     */
    @Test
    fun `two writers that read the same version race to the conditional update and exactly one wins`() = api {
        val f = family()
        for (u in updates(f)) {
            val responses = coroutineScope {
                val lock = dataSource.connection
                try {
                    lock.autoCommit = false
                    lock.prepareStatement("select 1 from ${u.table} where ${u.keyWhere} for update").use { st ->
                        u.keyArgs.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
                        st.executeQuery().close()
                    }
                    val pending = List(2) { async { client.put(u.path, Actor.OWNER, u.body(u.version)) } }
                    awaitBlockedUpdates(u.table, count = 2)
                    lock.rollback()
                    withTimeout(10_000) { pending.awaitAll() }
                } finally {
                    runCatching { lock.rollback() }
                    lock.close()
                }
            }
            val statuses = responses.map { it.status }
            assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses.sortedBy { it.value }, u.name)
            assertError(responses.single { it.status == HttpStatusCode.Conflict }, HttpStatusCode.Conflict, u.conflict, u.name)
            assertAdvanced(u, storedVersion(u))
            assertEquals(1, updateAudits(f.tree, u).size, u.name)
        }
    }

    @Test
    fun `a burst of concurrent writers from one version yields one update and one audit entry`() = api {
        val f = family()
        for (u in updates(f)) {
            val responses = coroutineScope { List(6) { async { client.put(u.path, Actor.OWNER, u.body(u.version)) } }.awaitAll() }
            val statuses = responses.map { it.status }
            assertEquals(1, statuses.count { it == HttpStatusCode.OK }, "${u.name}: $statuses")
            for (response in responses.filter { it.status != HttpStatusCode.OK }) {
                assertError(response, HttpStatusCode.Conflict, u.conflict, u.name)
            }
            assertAdvanced(u, storedVersion(u))
            assertEquals(1, updateAudits(f.tree, u).size, u.name)
        }
    }

    /**
     * Holds a row lock on [table] while [whileBlocked] runs: [blocked] is started, parked
     * in its UPDATE behind the lock, then the lock is released and its response returned.
     */
    private suspend fun lockedRace(
        table: String,
        keyWhere: String,
        keyArgs: List<UUID>,
        blocked: suspend () -> HttpResponse,
        whileBlocked: suspend () -> Unit,
    ): HttpResponse = coroutineScope {
        val lock = dataSource.connection
        try {
            lock.autoCommit = false
            lock.prepareStatement("select 1 from $table where $keyWhere for update").use { st ->
                keyArgs.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
                st.executeQuery().close()
            }
            val pending = async { blocked() }
            awaitBlockedUpdates(table, count = 1)
            whileBlocked()
            lock.rollback()
            withTimeout(10_000) { pending.await() }
        } finally {
            runCatching { lock.rollback() }
            lock.close()
        }
    }

    /**
     * Review regression: child 1's update waits on its row lock while a sibling's update
     * commits. Child 1's audit entry must show only child 1 changing; the sibling appears
     * identically in its before and after.
     */
    @Test
    fun `a child link audit entry never reports a sibling link committed meanwhile`() = api {
        val f = family()
        val t = path(f.tree)
        val sibling = client.post("$t/people", Actor.OWNER, encode(CreatePersonRequest(givenNames = "Pablo", sex = "MALE", isLiving = false)))
            .decoded<CreatePersonResponse>(HttpStatusCode.Created).person
        val siblingVersion = client.post("$t/unions/${f.union.id}/children", Actor.OWNER, encode(AddUnionChildRequest(sibling.id, "BIRTH")))
            .decoded<UnionDto>(HttpStatusCode.Created).children.single { it.childId == sibling.id }.version
        val first = updates(f).single { it.name == "child link" }

        val response = lockedRace(
            table = "union_child", keyWhere = first.keyWhere, keyArgs = first.keyArgs,
            blocked = { client.put(first.path, Actor.OWNER, first.body(first.version)) },
            whileBlocked = {
                client.put(
                    "$t/unions/${f.union.id}/children/${sibling.id}", Actor.OWNER, encode(UpdateUnionChildRequest("FOSTER", siblingVersion)),
                ).bodyWithStatus(HttpStatusCode.OK)
            },
        )
        val body = json.parseToJsonElement(response.bodyWithStatus(HttpStatusCode.OK))
        val newVersion = childIn(body, f.child.id).version()
        assertTrue(newVersion > first.version)
        assertEquals(newVersion, storedVersion(first))

        val (beforeJson, afterJson) = updateAudits(f.tree, first).single()
        val before = json.parseToJsonElement(beforeJson!!)
        val after = json.parseToJsonElement(afterJson!!)
        assertEquals(childIn(before, sibling.id), childIn(after, sibling.id), "sibling must be unchanged within child 1's entry")
        assertEquals("BIRTH", childIn(before, f.child.id).jsonObject.getValue("relation").jsonPrimitive.content)
        assertEquals(first.version, childIn(before, f.child.id).version())
        assertEquals("ADOPTED", childIn(after, f.child.id).jsonObject.getValue("relation").jsonPrimitive.content)
        assertEquals(newVersion, childIn(after, f.child.id).version())
        assertEquals(body, after)
    }

    /**
     * An event update that keeps its place, parked on its row lock while that place is
     * edited and committed, must record the same place before and after.
     */
    @Test
    fun `an event audit entry keeps an unchanged place identical before and after`() = api {
        val f = family()
        val t = path(f.tree)
        val event = updates(f).single { it.name == "event" }
        val response = lockedRace(
            table = "event", keyWhere = event.keyWhere, keyArgs = event.keyArgs,
            blocked = {
                client.put(
                    event.path, Actor.OWNER,
                    encode(UpdateEventRequest(personId = f.father.id, type = "BIRTH", date = "1946", placeId = f.place.id, version = 1)),
                )
            },
            whileBlocked = {
                client.put("$t/places/${f.place.id}", Actor.OWNER, encode(UpdatePlaceRequest("Sevilla", version = 1)))
                    .bodyWithStatus(HttpStatusCode.OK)
            },
        )
        response.bodyWithStatus(HttpStatusCode.OK)
        val (beforeJson, afterJson) = updateAudits(f.tree, event).single()
        val beforePlace = json.parseToJsonElement(beforeJson!!).jsonObject.getValue("place")
        val afterPlace = json.parseToJsonElement(afterJson!!).jsonObject.getValue("place")
        assertEquals(beforePlace, afterPlace)
    }

    /** Waits, at most 10 s, until [count] other sessions are waiting on a lock in an UPDATE of [table]. */
    private suspend fun awaitBlockedUpdates(table: String, count: Int) = withTimeout(10_000) {
        val sql = """
            select count(*) from pg_stat_activity
            where pid <> pg_backend_pid() and wait_event_type = 'Lock' and query ilike ?
        """.trimIndent()
        while (dataSource.query(sql, "%update%$table%")!!.toLong() < count) delay(25)
    }

    // --- Audit atomicity ----------------------------------------------------------------

    @Test
    fun `a failed audit insert rolls back every kind of update`() {
        lateinit var f: Family
        testApplication {
            application { module(settings()) }
            f = family()
        }
        val tree = TreeId(f.tree)
        val owner = AccountId(accounts.getValue(Actor.OWNER).id)
        fun call(name: String, block: suspend () -> Unit) = name to block
        // Service calls, as in PlaceAuditTest, so the database error itself can be inspected.
        val calls = listOf(
            call("person") {
                PersonService.updatePerson(
                    tree, PersonId(f.father.id), owner,
                    UpdatePersonRequest(givenNames = "Changed", sex = "MALE", isLiving = false, version = f.father.version),
                )
            },
            call("union") {
                UnionService.updateUnion(
                    tree, UnionId(f.union.id), owner, UpdateUnionRequest(f.father.id, f.mother.id, type = "PARTNERS", version = f.union.version),
                )
            },
            call("child link") {
                UnionService.updateChild(
                    tree, UnionId(f.union.id), PersonId(f.child.id), owner, UpdateUnionChildRequest("FOSTER", f.childLink.version),
                )
            },
            call("event") {
                EventService.updateEvent(
                    tree, EventId(f.event.id), owner, UpdateEventRequest(personId = f.father.id, type = "DEATH", version = f.event.version),
                )
            },
            call("place") { PlaceService.updatePlace(tree, PlaceId(f.place.id), owner, UpdatePlaceRequest("Changed", version = f.place.version)) },
            call("source") {
                SourceService.updateSource(
                    tree, SourceId(f.source.id), owner, UpdateSourceRequest(title = "Changed", type = "OTHER", version = f.source.version),
                )
            },
        )
        execute(
            """
            create function fail_update_audit() returns trigger language plpgsql as
            ${'$'}${'$'} begin raise exception '$TRIGGER_MESSAGE'; end ${'$'}${'$'};
            create trigger fail_update_audit before insert on audit_log
              for each row when (new.action = 'UPDATE' and new.tree_id = '${f.tree}') execute function fail_update_audit();
            """.trimIndent(),
        )
        try {
            val before = fingerprint(f.tree)
            for ((name, call) in calls) {
                val error = assertFails(name) { runBlocking { call() } }
                val cause = generateSequence(error) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()
                assertNotNull(cause, "$name: expected a PSQLException in the cause chain, got $error")
                assertEquals("P0001", cause.sqlState, name)
                assertEquals(TRIGGER_MESSAGE, cause.serverErrorMessage?.message, name)
            }
            assertEquals(before, fingerprint(f.tree))
        } finally {
            execute("drop trigger fail_update_audit on audit_log; drop function fail_update_audit();")
        }
        // Nothing was half-applied: the original versions are still current.
        runBlocking { for ((_, call) in calls) call() }
    }

    private fun execute(sql: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    // --- Authorization ------------------------------------------------------------------

    @Test
    fun `a member without an editing grant gets 403 for every update before the path ID or body is checked, and nothing changes`() = api {
        val f = family()
        val noEditAccess = "Your access to tree ${f.tree} doesn't allow changes"
        val notMember = "Not a member of tree ${f.tree}"
        val before = fingerprint(f.tree)
        for (u in updates(f)) {
            val malformedPath = u.path.substringBeforeLast('/') + "/not-a-uuid"
            for ((path, body, label) in listOf(
                Triple(u.path, u.body(u.version), "valid"),
                Triple(u.path, "{ not json", "malformed body"),
                Triple(u.path, "{}", "missing version"),
                Triple(malformedPath, u.body(u.version), "malformed path ID"),
            )) {
                assertError(client.put(path, Actor.RELATIVE, body), HttpStatusCode.Forbidden, noEditAccess, "relative ${u.name} $label")
            }
            assertError(client.put(u.path, Actor.INVITEE, "{ not json"), HttpStatusCode.Forbidden, notMember, "invitee ${u.name}")
            assertError(client.put(u.path, Actor.OTHER_OWNER, u.body(u.version)), HttpStatusCode.Forbidden, notMember, "other owner ${u.name}")
        }
        assertEquals(before, fingerprint(f.tree))
    }

    // --- Tenant isolation ---------------------------------------------------------------

    @Test
    fun `another tree's records and references read like missing IDs and change nothing`() = api {
        val f = family()
        val g = foreign
        val a = path(f.tree)
        val own = updates(f)
        // Each case names one ID twice: tree B's row, then an ID that exists nowhere.
        data class Case(val name: String, val foreignId: String, val request: (id: String) -> Pair<String, String>)
        val cases = listOf(
            Case("person target", g.father.id) { id -> "$a/people/$id" to own[0].body(1) },
            Case("union target", g.union.id) { id -> "$a/unions/$id" to own[1].body(1) },
            Case("child link union", g.union.id) { id -> "$a/unions/$id/children/${f.child.id}" to own[2].body(1) },
            Case("child link child", g.child.id) { id -> "$a/unions/${f.union.id}/children/$id" to own[2].body(1) },
            Case("event target", g.event.id) { id -> "$a/events/$id" to own[3].body(1) },
            Case("place target", g.place.id) { id -> "$a/places/$id" to own[4].body(1) },
            Case("source target", g.source.id) { id -> "$a/sources/$id" to own[5].body(1) },
            Case("first partner", g.father.id) { id ->
                own[1].path to encode(UpdateUnionRequest(id, f.mother.id, type = "MARRIED", version = 1))
            },
            Case("second partner", g.mother.id) { id ->
                own[1].path to encode(UpdateUnionRequest(f.father.id, id, type = "MARRIED", version = 1))
            },
            Case("event person", g.father.id) { id -> own[3].path to encode(UpdateEventRequest(personId = id, type = "BIRTH", version = 1)) },
            Case("event union", g.union.id) { id -> own[3].path to encode(UpdateEventRequest(unionId = id, type = "MARRIAGE", version = 1)) },
            Case("event place", g.place.id) { id ->
                own[3].path to encode(UpdateEventRequest(personId = f.father.id, type = "BIRTH", placeId = id, version = 1))
            },
        )
        val beforeA = fingerprint(f.tree)
        val beforeB = fingerprint(g.tree)
        for (case in cases) {
            val missing = UUID.randomUUID().toString()
            val (foreignPath, foreignBody) = case.request(case.foreignId)
            val (missingPath, missingBody) = case.request(missing)
            val foreignResponse = client.put(foreignPath, Actor.OWNER, foreignBody)
            val missingResponse = client.put(missingPath, Actor.OWNER, missingBody)
            val foreignText = foreignResponse.bodyAsText()
            assertEquals(HttpStatusCode.NotFound, foreignResponse.status, "${case.name} (foreign): $foreignText")
            assertEquals(HttpStatusCode.NotFound, missingResponse.status, "${case.name} (missing)")
            assertEquals(missingResponse.bodyAsText().replace(missing, "<id>"), foreignText.replace(case.foreignId, "<id>"), case.name)
            for (id in listOf(g.tree, g.father.id, g.mother.id, g.child.id, g.union.id, g.event.id, g.place.id, g.source.id) - case.foreignId) {
                assertFalse(id in foreignText, "${case.name} leaked tree B's ID $id: $foreignText")
            }
            listOf("Quispe", "Lima").forEach { assertFalse(it in foreignText, "${case.name} leaked '$it': $foreignText") }
        }
        assertEquals(beforeA, fingerprint(f.tree))
        assertEquals(beforeB, fingerprint(g.tree))
    }

    // --- Validation ---------------------------------------------------------------------

    @Test
    fun `invalid IDs, values and references fail like their create routes and change nothing`() = api {
        val f = family()
        val t = path(f.tree)
        val own = updates(f)
        val bad = "not-a-uuid"
        val missing = UUID.randomUUID().toString()
        fun id(field: String) = "$field must be a UUID"
        data class Case(val name: String, val path: String, val body: String, val status: HttpStatusCode, val message: String)
        val badRequest = HttpStatusCode.BadRequest
        val notFound = HttpStatusCode.NotFound
        val cases = listOf(
            // Path IDs.
            Case("person path", "$t/people/$bad", own[0].body(1), badRequest, id("personId")),
            Case("union path", "$t/unions/$bad", own[1].body(1), badRequest, id("unionId")),
            Case("child link union path", "$t/unions/$bad/children/${f.child.id}", own[2].body(1), badRequest, id("unionId")),
            Case("child link child path", "$t/unions/${f.union.id}/children/$bad", own[2].body(1), badRequest, id("childId")),
            Case("event path", "$t/events/$bad", own[3].body(1), badRequest, id("eventId")),
            Case("place path", "$t/places/$bad", own[4].body(1), badRequest, id("placeId")),
            Case("source path", "$t/sources/$bad", own[5].body(1), badRequest, id("sourceId")),
            // Body IDs.
            Case("first partner", own[1].path, encode(UpdateUnionRequest(bad, type = "MARRIED", version = 1)), badRequest, id("partner1Id")),
            Case(
                "second partner", own[1].path, encode(UpdateUnionRequest(f.father.id, bad, type = "MARRIED", version = 1)),
                badRequest, id("partner2Id"),
            ),
            Case("event person", own[3].path, encode(UpdateEventRequest(personId = bad, type = "BIRTH", version = 1)), badRequest, id("personId")),
            Case("event union", own[3].path, encode(UpdateEventRequest(unionId = bad, type = "MARRIAGE", version = 1)), badRequest, id("unionId")),
            Case(
                "event place", own[3].path, encode(UpdateEventRequest(personId = f.father.id, type = "BIRTH", placeId = bad, version = 1)),
                badRequest, id("placeId"),
            ),
            // Values.
            Case(
                "sex", own[0].path, encode(UpdatePersonRequest(givenNames = "Ana", sex = "ROBOT", isLiving = false, version = 1)),
                badRequest, "Unknown sex 'ROBOT'",
            ),
            Case(
                "blank given names", own[0].path, encode(UpdatePersonRequest(givenNames = " ", sex = "FEMALE", isLiving = false, version = 1)),
                badRequest, "Given names must not be blank",
            ),
            Case(
                "union type", own[1].path, encode(UpdateUnionRequest(f.father.id, f.mother.id, type = "ROBOT", version = 1)),
                badRequest, "Unknown union type 'ROBOT'",
            ),
            Case(
                "same partner", own[1].path, encode(UpdateUnionRequest(f.father.id, f.father.id.uppercase(), type = "MARRIED", version = 1)),
                badRequest, "A union's two partners must be different people",
            ),
            Case(
                "inverted dates", own[1].path,
                encode(UpdateUnionRequest(f.father.id, f.mother.id, type = "MARRIED", startDate = "1990", endDate = "1970", version = 1)),
                badRequest, "Start date must not be after end date",
            ),
            Case("child relation", own[2].path, encode(UpdateUnionChildRequest("ROBOT", 1)), badRequest, "Unknown child relation 'ROBOT'"),
            Case(
                "event type", own[3].path, encode(UpdateEventRequest(personId = f.father.id, type = "ROBOT", version = 1)),
                badRequest, "Unknown event type 'ROBOT'",
            ),
            Case(
                "event with no subject", own[3].path, encode(UpdateEventRequest(type = "BIRTH", version = 1)),
                badRequest, "An event must belong to exactly one of personId or unionId",
            ),
            Case(
                "event with two subjects", own[3].path,
                encode(UpdateEventRequest(personId = f.father.id, unionId = f.union.id, type = "BIRTH", version = 1)),
                badRequest, "An event must belong to exactly one of personId or unionId",
            ),
            Case("blank place name", own[4].path, encode(UpdatePlaceRequest(" ", version = 1)), badRequest, "Place name must not be blank"),
            Case(
                "source type", own[5].path, encode(UpdateSourceRequest(title = "T", type = "ROBOT", version = 1)),
                badRequest, "Unknown source type 'ROBOT'",
            ),
            Case(
                "blank source title", own[5].path, encode(UpdateSourceRequest(title = " ", type = "OTHER", version = 1)),
                badRequest, "Source title must not be blank",
            ),
            // Bodies that can't be decoded, including a missing version.
            Case("person without version", own[0].path, """{"givenNames":"Ana","sex":"FEMALE","isLiving":false}""", badRequest, MALFORMED_BODY_MESSAGE),
            Case("place without version", own[4].path, """{"name":"Sevilla"}""", badRequest, MALFORMED_BODY_MESSAGE),
            Case("child link without version", own[2].path, """{"relation":"STEP"}""", badRequest, MALFORMED_BODY_MESSAGE),
            Case("union syntax", own[1].path, "{ not json", badRequest, MALFORMED_BODY_MESSAGE),
            // Well-formed references that don't exist.
            Case(
                "missing partner", own[1].path, encode(UpdateUnionRequest(f.father.id, missing, type = "MARRIED", version = 1)),
                notFound, "No person with id $missing",
            ),
            Case(
                "missing event place", own[3].path,
                encode(UpdateEventRequest(personId = f.father.id, type = "BIRTH", placeId = missing, version = 1)),
                notFound, "No place with id $missing",
            ),
            Case(
                "unlinked child", "$t/unions/${f.union.id}/children/${f.father.id}", own[2].body(1),
                notFound, "No child ${f.father.id} in union ${f.union.id}",
            ),
            // The record is resolved before the body's IDs are parsed, so a missing one stays a 404.
            Case(
                "missing union, malformed partner", "$t/unions/$missing", encode(UpdateUnionRequest(bad, type = "MARRIED", version = 1)),
                notFound, "No union with id $missing",
            ),
            Case(
                "missing event, malformed person", "$t/events/$missing", encode(UpdateEventRequest(personId = bad, type = "BIRTH", version = 1)),
                notFound, "No event with id $missing",
            ),
        )
        val before = fingerprint(f.tree)
        for (case in cases) {
            val response = client.put(case.path, Actor.OWNER, case.body)
            assertError(response, case.status, case.message, case.name)
            assertFalse(bad in response.bodyAsText(), "${case.name} echoed the malformed input")
        }
        assertEquals(before, fingerprint(f.tree))
    }
}
