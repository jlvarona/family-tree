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
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.api.UpdatePersonRequest
import com.moxietech.family_tree.api.UpdatePlaceRequest
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
import com.moxietech.family_tree.server.config.OidcSettings
import com.moxietech.family_tree.server.config.TestJwks
import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.VERSION_PARAM_MESSAGE
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
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.postgresql.util.PSQLException
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * FT-001 versioned deletes over HTTP, through the production [module] and
 * verifier, on a disposable PostgreSQL 16 container migrated by Flyway.
 *
 * Contract: `DELETE …/{id}?version=N` (N the current version, a positive
 * decimal integer given once) → `204` with no body. Owner-only; foreign and
 * missing IDs are the same `404`; a stale version or a record that something
 * in its tree still references (a foreign key or a same-tree citation) is a
 * `409` that writes nothing. A success audits before = the record, after = null.
 *
 * Actors and trees as in `VersionedUpdateTest`. "Nothing changed" is checked
 * against a fingerprint of every row, citations and audit entries included.
 */
class VersionedDeleteTest {

    private enum class Actor(val subject: String, val email: String) {
        OWNER("ft001-del-owner", "ft001-del-owner@example.com"),
        OTHER_OWNER("ft001-del-other", "ft001-del-other@example.com"),
        RELATIVE("ft001-del-relative", "ft001-del-relative@example.com"),
        INVITEE("ft001-del-invitee", "ft001-del-invitee@example.com"),
    }

    private data class Family(
        val tree: String,
        val father: PersonDto,
        val mother: PersonDto,
        val child: PersonDto,
        val union: UnionDto,
        val place: PlaceDto,
        val source: SourceDto,
        /** The father's birth at [place]. */
        val event: EventDto,
    )

    /** One DELETE route applied to one record. */
    private data class Target(
        val name: String,
        val entityType: String,
        val entityId: String,
        val label: String,
        val path: String,
        val version: Long,
        /** The audit "before" a successful delete must record. */
        val before: JsonElement,
        val table: String,
        val keyWhere: String,
        val keyArgs: List<UUID>,
    ) {
        val stale get() = "$label $entityId has been changed since you read it; reload it and try again"
        val referenced get() = "$label $entityId is still referenced by other records; remove those references first"
    }

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val ISSUER = "http://keycloak.test/realms/family-tree"
        private const val TRIGGER_MESSAGE = "FT-001 test-only trigger rejected the delete audit"
        private val json = Json { ignoreUnknownKeys = true }
        private val tables =
            listOf("person", "union_record", "union_child", "event", "place", "source", "citation", "membership", "audit_log")

        private lateinit var dataSource: HikariDataSource
        private lateinit var jwks: TestJwks
        private lateinit var accounts: Map<Actor, AccountDto>
        private lateinit var treeA: String
        private lateinit var treeB: String
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
                treeA = client.createTree(token(Actor.OWNER), "FT-001 delete A").id
                treeB = client.createTree(token(Actor.OTHER_OWNER), "FT-001 delete B").id
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

        private suspend fun HttpClient.send(method: HttpMethod, path: String, actor: Actor, body: String? = null): HttpResponse =
            request(path) {
                this.method = method
                bearerAuth(token(actor))
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }

        private suspend fun HttpClient.post(path: String, actor: Actor, body: String) = send(HttpMethod.Post, path, actor, body)

        /** DELETE [path] with `?version=` followed by [version] verbatim, or no query when null. */
        private suspend fun HttpClient.delete(path: String, actor: Actor, version: String?) =
            send(HttpMethod.Delete, if (version == null) path else "$path?version=$version", actor)

        private suspend fun HttpClient.person(tree: String, owner: Actor, given: String, living: Boolean = false): PersonDto =
            post("${path(tree)}/people", owner, encode(CreatePersonRequest(givenNames = given, sex = "UNKNOWN", isLiving = living)))
                .decoded<CreatePersonResponse>(HttpStatusCode.Created).person

        private suspend fun HttpClient.union(tree: String, owner: Actor, partner1: String, partner2: String? = null): UnionDto =
            post("${path(tree)}/unions", owner, encode(CreateUnionRequest(partner1, partner2, type = "PARTNERS")))
                .decoded<UnionDto>(HttpStatusCode.Created)

        private suspend fun HttpClient.newFamily(tree: String, owner: Actor, surname: String = "Ruiz", placeName: String = "Cádiz"): Family {
            val t = path(tree)
            suspend fun person(given: String) = post(
                "$t/people", owner, encode(CreatePersonRequest(givenNames = given, surnamePaternal = surname, sex = "UNKNOWN", isLiving = false)),
            ).decoded<CreatePersonResponse>(HttpStatusCode.Created).person
            val father = person("Mateo")
            val mother = person("Julia")
            val child = person("Ines")
            val created = post("$t/unions", owner, encode(CreateUnionRequest(father.id, mother.id, type = "MARRIED", startDate = "1970")))
                .decoded<UnionDto>(HttpStatusCode.Created)
            val union = post("$t/unions/${created.id}/children", owner, encode(AddUnionChildRequest(child.id, "BIRTH")))
                .decoded<UnionDto>(HttpStatusCode.Created)
            val place = post("$t/places", owner, encode(CreatePlaceRequest(placeName))).decoded<PlaceDto>(HttpStatusCode.Created)
            val source = post("$t/sources", owner, encode(CreateSourceRequest(title = "Parish register", type = "CHURCH_RECORD")))
                .decoded<SourceDto>(HttpStatusCode.Created)
            val event = post(
                "$t/events", owner, encode(CreateEventRequest(personId = father.id, type = "BIRTH", date = "1945", placeId = place.id)),
            ).decoded<EventDto>(HttpStatusCode.Created)
            return Family(tree, father, mother, child, union, place, source, event)
        }
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(settings()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.family() = client.newFamily(treeA, Actor.OWNER)

    private fun personTarget(tree: String, p: PersonDto) = Target(
        "person", "Person", p.id, "Person", "${path(tree)}/people/${p.id}", p.version, AuditJson.encodeToJsonElement(p),
        "person", "id = ?", listOf(uuid(p.id)),
    )

    private fun unionTarget(tree: String, u: UnionDto) = Target(
        "union", "Union", u.id, "Union", "${path(tree)}/unions/${u.id}", u.version, AuditJson.encodeToJsonElement(u),
        "union_record", "id = ?", listOf(uuid(u.id)),
    )

    private fun placeTarget(tree: String, p: PlaceDto) = Target(
        "place", "Place", p.id, "Place", "${path(tree)}/places/${p.id}", p.version, AuditJson.encodeToJsonElement(p),
        "place", "id = ?", listOf(uuid(p.id)),
    )

    private fun sourceTarget(tree: String, s: SourceDto) = Target(
        "source", "Source", s.id, "Source", "${path(tree)}/sources/${s.id}", s.version, AuditJson.encodeToJsonElement(s),
        "source", "id = ?", listOf(uuid(s.id)),
    )

    private fun eventTarget(tree: String, e: EventDto) = Target(
        "event", "Event", e.id, "Event", "${path(tree)}/events/${e.id}", e.version, AuditJson.encodeToJsonElement(e),
        "event", "id = ?", listOf(uuid(e.id)),
    )

    private fun linkTarget(f: Family) = Target(
        "child link", "UnionChild", "${f.union.id}:${f.child.id}", "Child link",
        "${path(f.tree)}/unions/${f.union.id}/children/${f.child.id}",
        f.union.children.single { it.childId == f.child.id }.version, AuditJson.encodeToJsonElement(f.union),
        "union_child", "union_id = ? and child_id = ?", listOf(uuid(f.union.id), uuid(f.child.id)),
    )

    /** All six routes for [f], in an order where each record is unreferenced once the earlier ones are gone. */
    private fun deletions(f: Family): List<Target> = listOf(
        linkTarget(f),
        eventTarget(f.tree, f.event),
        placeTarget(f.tree, f.place),
        sourceTarget(f.tree, f.source),
        unionTarget(f.tree, f.union.copy(children = emptyList())),
        personTarget(f.tree, f.child),
    )

    private fun fingerprint(treeId: String): Map<String, String?> = tables.associateWith { table ->
        dataSource.query("select string_agg(t::text, E'\\n' order by t::text) from $table t where tree_id = ?", uuid(treeId))
    }

    private fun rows(t: Target): Long =
        dataSource.query("select count(*) from ${t.table} where ${t.keyWhere}", *t.keyArgs.toTypedArray())!!.toLong()

    private fun deleteAudits(tree: String, t: Target): List<Pair<String?, String?>> = dataSource.connection.use { c ->
        c.prepareStatement(
            "select actor_account_id, before_json, after_json from audit_log " +
                "where tree_id = ? and entity_type = ? and entity_id = ? and action = 'DELETE'",
        ).use { st ->
            st.setObject(1, uuid(tree))
            st.setString(2, t.entityType)
            st.setString(3, t.entityId)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        assertEquals(accounts.getValue(Actor.OWNER).id, rs.getString("actor_account_id"), t.name)
                        add(rs.getString("before_json") to rs.getString("after_json"))
                    }
                }
            }
        }
    }

    /** A same-tree (or deliberately foreign) citation row; FT-002 has no route yet, so it is inserted directly. */
    private fun cite(tree: String, source: String, targetType: String, targetId: String, c: Connection? = null) {
        val sql = "insert into citation (tree_id, source_id, target_type, target_id, confidence) values (?, ?, ?, ?, 2)"
        fun insertWith(conn: Connection) = conn.prepareStatement(sql).use { st ->
            st.setObject(1, uuid(tree)); st.setObject(2, uuid(source)); st.setString(3, targetType); st.setObject(4, uuid(targetId))
            st.executeUpdate()
        }
        if (c != null) insertWith(c) else dataSource.connection.use { insertWith(it) }
    }

    private fun execute(sql: String, vararg args: Any?) {
        dataSource.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.execute()
            }
        }
    }

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, message: String, context: String) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, "$context: $body")
        assertEquals(message, json.decodeFromString<ApiErrorResponse>(body).message, context)
        assertFalse("Exception" in body || "SQL" in body || "fkey" in body, "$context exposed internals: $body")
    }

    /** Waits, at most 10 s, until [count] other sessions wait on a lock in a statement touching [table]. */
    private suspend fun awaitBlocked(table: String, count: Int = 1) = withTimeout(10_000) {
        val sql = """
            select count(*) from pg_stat_activity
            where pid <> pg_backend_pid() and wait_event_type = 'Lock' and query ilike ?
        """.trimIndent()
        while (dataSource.query(sql, "%$table%")!!.toLong() < count) delay(25)
    }

    // --- Successful deletes -------------------------------------------------------------

    @Test
    fun `every delete removes an unreferenced record with 204 and audits before with a null after`() = api {
        val f = family()
        for (t in deletions(f)) {
            val response = client.delete(t.path, Actor.OWNER, t.version.toString())
            assertEquals(HttpStatusCode.NoContent, response.status, "${t.name}: ${response.bodyAsText()}")
            assertEquals("", response.bodyAsText(), t.name)
            assertEquals(0, rows(t), t.name)
            val (before, after) = deleteAudits(f.tree, t).single()
            assertEquals(t.before, json.parseToJsonElement(before!!), "${t.name} audit before")
            assertNull(after, "${t.name} audit after")
        }
        // Earlier audit entries for the deleted records are kept.
        val history = dataSource.query(
            "select count(*) from audit_log where tree_id = ? and entity_id = ? and action = 'CREATE'", uuid(f.tree), f.child.id,
        )!!.toLong()
        assertEquals(1, history)
        // Reads no longer return them; the remaining people are untouched.
        val t = path(f.tree)
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "$t/people/${f.child.id}", Actor.OWNER).status)
        val graph = client.send(HttpMethod.Get, "$t/graph", Actor.OWNER).decoded<TreeGraphDto>()
        assertTrue(graph.unions.none { it.id == f.union.id })
        assertTrue(graph.people.any { it.id == f.father.id } && graph.people.none { it.id == f.child.id })
        assertTrue(client.send(HttpMethod.Get, "$t/places", Actor.OWNER).decoded<List<PlaceDto>>().none { it.id == f.place.id })
        assertTrue(client.send(HttpMethod.Get, "$t/sources", Actor.OWNER).decoded<List<SourceDto>>().none { it.id == f.source.id })
    }

    // --- References ---------------------------------------------------------------------

    @Test
    fun `a record any other record in its tree references is a 409 that writes nothing`() = api {
        val f = family()
        val a = f.tree
        // One person per kind of reference, so each check is exercised on its own.
        val member = client.person(a, Actor.OWNER, "Member")
        execute(
            "update membership set person_id = ? where tree_id = ? and account_id = ?",
            uuid(member.id), uuid(a), uuid(accounts.getValue(Actor.INVITEE).id),
        )
        val subject = client.person(a, Actor.OWNER, "Subject")
        client.post("${path(a)}/events", Actor.OWNER, encode(CreateEventRequest(personId = subject.id, type = "OCCUPATION")))
            .bodyWithStatus(HttpStatusCode.Created)
        val cited = client.person(a, Actor.OWNER, "Cited", living = true) // living: private to members, still protected
        cite(a, f.source.id, "PERSON", cited.id)
        val firstPartner = client.person(a, Actor.OWNER, "First")
        val secondPartner = client.person(a, Actor.OWNER, "Second")
        val anchor = client.person(a, Actor.OWNER, "Anchor")
        val solo = client.union(a, Actor.OWNER, firstPartner.id) // firstPartner: partner 1 only
        val pair = client.union(a, Actor.OWNER, anchor.id, secondPartner.id) // secondPartner: partner 2 only
        client.post("${path(a)}/events", Actor.OWNER, encode(CreateEventRequest(unionId = pair.id, type = "MARRIAGE")))
            .bodyWithStatus(HttpStatusCode.Created)
        cite(a, f.source.id, "UNION", solo.id)
        cite(a, f.source.id, "EVENT", f.event.id)

        val cases = listOf(
            "partner and event subject" to personTarget(a, f.father),
            "child in a link" to personTarget(a, f.child),
            "linked by a membership" to personTarget(a, member),
            "event subject only" to personTarget(a, subject),
            "cited living person" to personTarget(a, cited),
            "first partner only" to personTarget(a, firstPartner),
            "second partner only" to personTarget(a, secondPartner),
            "union with a child" to unionTarget(a, f.union),
            "union with an event" to unionTarget(a, pair),
            "cited union" to unionTarget(a, solo),
            "cited event" to eventTarget(a, f.event),
            "place used by an event" to placeTarget(a, f.place),
            "source with citations" to sourceTarget(a, f.source),
        )
        val before = fingerprint(a)
        for ((name, t) in cases) {
            assertError(client.delete(t.path, Actor.OWNER, t.version.toString()), HttpStatusCode.Conflict, t.referenced, name)
        }
        assertEquals(before, fingerprint(a))
    }

    @Test
    fun `another tree's records read like missing IDs, and its citations neither block nor leak`() = api {
        val f = family()
        val g = foreign
        val a = path(f.tree)
        val lone = client.person(f.tree, Actor.OWNER, "Lone")
        // Invalid data V2's preflight would reject: tree B's citation naming tree A's person.
        cite(g.tree, g.source.id, "PERSON", lone.id)

        val cases = listOf(
            "person" to { id: String -> "$a/people/$id" } to g.father.id,
            "union" to { id: String -> "$a/unions/$id" } to g.union.id,
            "child link union" to { id: String -> "$a/unions/$id/children/${f.child.id}" } to g.union.id,
            "child link child" to { id: String -> "$a/unions/${f.union.id}/children/$id" } to g.child.id,
            "event" to { id: String -> "$a/events/$id" } to g.event.id,
            "place" to { id: String -> "$a/places/$id" } to g.place.id,
            "source" to { id: String -> "$a/sources/$id" } to g.source.id,
        )
        val beforeA = fingerprint(f.tree)
        val beforeB = fingerprint(g.tree)
        for ((named, foreignId) in cases) {
            val (name, route) = named
            val missing = UUID.randomUUID().toString()
            val foreignResponse = client.delete(route(foreignId), Actor.OWNER, "1")
            val missingResponse = client.delete(route(missing), Actor.OWNER, "1")
            val text = foreignResponse.bodyAsText()
            assertEquals(HttpStatusCode.NotFound, foreignResponse.status, "$name: $text")
            assertEquals(HttpStatusCode.NotFound, missingResponse.status, name)
            assertEquals(missingResponse.bodyAsText().replace(missing, "<id>"), text.replace(foreignId, "<id>"), name)
            listOf("Quispe", "Lima", g.tree).forEach { assertFalse(it in text, "$name leaked '$it': $text") }
        }
        assertEquals(beforeA, fingerprint(f.tree))
        assertEquals(beforeB, fingerprint(g.tree))

        assertEquals(HttpStatusCode.NoContent, client.delete("$a/people/${lone.id}", Actor.OWNER, "1").status)
        assertEquals(beforeB, fingerprint(g.tree), "tree B's rows, including its citation, are untouched")
    }

    // --- Versions and competing writers ----------------------------------------------------

    @Test
    fun `a stale or unknown version is a 409 that writes nothing, and the current one deletes`() = api {
        val lone = client.person(treeA, Actor.OWNER, "Versioned")
        val p = "${path(treeA)}/people/${lone.id}"
        client.send(
            HttpMethod.Put, p, Actor.OWNER, encode(UpdatePersonRequest(givenNames = "Versioned 2", sex = "UNKNOWN", isLiving = false, version = 1)),
        ).bodyWithStatus(HttpStatusCode.OK)
        val t = personTarget(treeA, lone)
        val before = fingerprint(treeA)
        assertError(client.delete(p, Actor.OWNER, "1"), HttpStatusCode.Conflict, t.stale, "stale")
        assertError(client.delete(p, Actor.OWNER, "99"), HttpStatusCode.Conflict, t.stale, "future")
        assertEquals(before, fingerprint(treeA))
        assertEquals(HttpStatusCode.NoContent, client.delete(p, Actor.OWNER, "2").status)
        // After the delete, updates and deletes of it are the generic 404.
        assertError(
            client.send(
                HttpMethod.Put, p, Actor.OWNER, encode(UpdatePersonRequest(givenNames = "Ghost", sex = "UNKNOWN", isLiving = false, version = 3)),
            ),
            HttpStatusCode.NotFound, "No person with id ${lone.id}", "update after delete",
        )
        assertError(client.delete(p, Actor.OWNER, "3"), HttpStatusCode.NotFound, "No person with id ${lone.id}", "delete after delete")
    }

    /**
     * A delete and an update from the same version park behind a test-held row lock;
     * when it is released exactly one succeeds and the other is a stale 409.
     */
    @Test
    fun `a delete and an update racing from one version leave exactly one winner`() = api {
        repeat(2) { round ->
            val place = client.post("${path(treeA)}/places", Actor.OWNER, encode(CreatePlaceRequest("Race $round")))
                .decoded<PlaceDto>(HttpStatusCode.Created)
            val t = placeTarget(treeA, place)
            val responses = coroutineScope {
                val lock = dataSource.connection
                try {
                    lock.autoCommit = false
                    lock.prepareStatement("select 1 from place where id = ? for update").use { st ->
                        st.setObject(1, uuid(place.id)); st.executeQuery().close()
                    }
                    val del = async { client.delete(t.path, Actor.OWNER, "1") }
                    val put = async { client.send(HttpMethod.Put, t.path, Actor.OWNER, encode(UpdatePlaceRequest("Renamed", version = 1))) }
                    awaitBlocked("place", count = 2)
                    lock.rollback()
                    withTimeout(10_000) { listOf(del, put).awaitAll() }
                } finally {
                    runCatching { lock.rollback() }
                    lock.close()
                }
            }
            val (del, put) = responses
            val deleted = del.status == HttpStatusCode.NoContent
            if (deleted) {
                assertError(put, HttpStatusCode.Conflict, t.stale, "update lost")
                assertEquals(0, rows(t))
                assertEquals(1, deleteAudits(treeA, t).size)
            } else {
                assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
                assertError(del, HttpStatusCode.Conflict, t.stale, "delete lost")
                assertEquals(1, rows(t))
                assertEquals(0, deleteAudits(treeA, t).size)
            }
        }
    }

    @Test
    fun `concurrent deletes of one record delete it once and audit it once`() = api {
        val f = family()
        val t = linkTarget(f)
        val responses = coroutineScope { List(6) { async { client.delete(t.path, Actor.OWNER, "1") } }.awaitAll() }
        val statuses = responses.map { it.status }
        assertEquals(1, statuses.count { it == HttpStatusCode.NoContent }, "statuses: $statuses")
        // A loser that resolved the link before the winner committed is stale; one that came later finds nothing.
        for (r in responses.filter { it.status != HttpStatusCode.NoContent }) {
            assertTrue(r.status == HttpStatusCode.Conflict || r.status == HttpStatusCode.NotFound, "${r.status}")
        }
        assertEquals(0, rows(t))
        assertEquals(1, deleteAudits(f.tree, t).size)
    }

    // --- Races with reference writers ----------------------------------------------------

    /**
     * A concurrent writer inserts a foreign-key reference and hasn't committed: the
     * delete's checks can't see it, so its DELETE runs and waits on the writer's
     * `FOR KEY SHARE` lock. After the writer commits, PostgreSQL rejects the DELETE
     * (23503) and the API returns the referenced 409 with nothing written.
     */
    @Test
    fun `a reference committed by a concurrent writer during the delete wins, through the foreign key`() = api {
        val f = family()
        val spare = client.post("${path(f.tree)}/places", Actor.OWNER, encode(CreatePlaceRequest("Spare")))
            .decoded<PlaceDto>(HttpStatusCode.Created)
        val bare = client.union(f.tree, Actor.OWNER, f.mother.id)
        val loner = client.person(f.tree, Actor.OWNER, "Loner")
        data class Race(val target: Target, val insert: String, val args: List<Any>)
        val races = listOf(
            Race(
                placeTarget(f.tree, spare),
                "insert into event (tree_id, person_id, place_id, type) values (?, ?, ?, 'RESIDENCE')",
                listOf(uuid(f.tree), uuid(f.father.id), uuid(spare.id)),
            ),
            Race(
                unionTarget(f.tree, bare),
                "insert into union_child (tree_id, union_id, child_id, relation) values (?, ?, ?, 'STEP')",
                listOf(uuid(f.tree), uuid(bare.id), uuid(f.child.id)),
            ),
            Race(
                personTarget(f.tree, loner),
                "insert into event (tree_id, person_id, type) values (?, ?, 'BURIAL')",
                listOf(uuid(f.tree), uuid(loner.id)),
            ),
        )
        for (race in races) {
            val t = race.target
            val response = coroutineScope {
                val writer = dataSource.connection
                try {
                    writer.autoCommit = false
                    writer.prepareStatement(race.insert).use { st ->
                        race.args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                        st.executeUpdate()
                    }
                    val pending = async { client.delete(t.path, Actor.OWNER, t.version.toString()) }
                    awaitBlocked(t.table)
                    writer.commit()
                    withTimeout(10_000) { pending.await() }
                } finally {
                    runCatching { writer.rollback() }
                    writer.close()
                }
            }
            assertError(response, HttpStatusCode.Conflict, t.referenced, t.name)
            assertEquals(1, rows(t), t.name)
            assertEquals(0, deleteAudits(f.tree, t).size, t.name)
        }
    }

    /**
     * A citation has no foreign key. A citation writer that follows the FT-002 lock
     * contract (lock the target `FOR KEY SHARE`, then insert) makes the delete's
     * DELETE wait; the delete's second citation check then sees the committed
     * citation and rolls back with the referenced 409.
     */
    @Test
    fun `a citation committed under the FT-002 lock contract during the delete wins`() = api {
        val f = family()
        val loner = client.person(f.tree, Actor.OWNER, "Uncited")
        val bare = client.union(f.tree, Actor.OWNER, loner.id)
        val event = client.post("${path(f.tree)}/events", Actor.OWNER, encode(CreateEventRequest(unionId = bare.id, type = "OTHER")))
            .decoded<EventDto>(HttpStatusCode.Created)
        val other = client.person(f.tree, Actor.OWNER, "AlsoUncited")
        // Both targets are otherwise unreferenced, so only the racing citation can block them.
        val races = listOf(
            Triple(eventTarget(f.tree, event), "event", "EVENT"),
            Triple(personTarget(f.tree, other), "person", "PERSON"),
        )
        for ((t, table, type) in races) {
            val response = coroutineScope {
                val writer = dataSource.connection
                try {
                    writer.autoCommit = false
                    writer.prepareStatement("select 1 from $table where id = ? and tree_id = ? for key share").use { st ->
                        st.setObject(1, uuid(t.entityId)); st.setObject(2, uuid(f.tree))
                        st.executeQuery().use { assertTrue(it.next(), "target exists") }
                    }
                    cite(f.tree, f.source.id, type, t.entityId, writer)
                    val pending = async { client.delete(t.path, Actor.OWNER, t.version.toString()) }
                    awaitBlocked(table)
                    writer.commit()
                    withTimeout(10_000) { pending.await() }
                } finally {
                    runCatching { writer.rollback() }
                    writer.close()
                }
            }
            assertError(response, HttpStatusCode.Conflict, t.referenced, t.name)
            assertEquals(1, rows(t), t.name)
            assertEquals(0, deleteAudits(f.tree, t).size, t.name)
        }
        // Unions use the same post-DELETE check; their committed-citation case is in the 409 test above.
    }

    // --- Audit atomicity ----------------------------------------------------------------

    @Test
    fun `a failed audit insert rolls back every kind of delete`() {
        lateinit var f: Family
        lateinit var loner: PersonDto
        lateinit var bare: UnionDto
        lateinit var spare: PlaceDto
        testApplication {
            application { module(settings()) }
            f = family()
            loner = client.person(f.tree, Actor.OWNER, "Doomed")
            bare = client.union(f.tree, Actor.OWNER, f.mother.id)
            spare = client.post("${path(f.tree)}/places", Actor.OWNER, encode(CreatePlaceRequest("Spare")))
                .decoded<PlaceDto>(HttpStatusCode.Created)
        }
        val tree = TreeId(f.tree)
        val owner = AccountId(accounts.getValue(Actor.OWNER).id)
        val v = listOf("1")
        fun call(name: String, block: suspend () -> Unit) = name to block
        // Unreferenced records of every kind, each deletable on its own.
        val calls = listOf(
            call("child link") { UnionService.deleteChild(tree, UnionId(f.union.id), PersonId(f.child.id), owner, v) },
            call("event") { EventService.deleteEvent(tree, EventId(f.event.id), owner, v) },
            call("place") { PlaceService.deletePlace(tree, PlaceId(spare.id), owner, v) },
            call("source") { SourceService.deleteSource(tree, SourceId(f.source.id), owner, v) },
            call("union") { UnionService.deleteUnion(tree, UnionId(bare.id), owner, v) },
            call("person") { PersonService.deletePerson(tree, PersonId(loner.id), owner, v) },
        )
        execute(
            """
            create function fail_delete_audit() returns trigger language plpgsql as
            ${'$'}${'$'} begin raise exception '$TRIGGER_MESSAGE'; end ${'$'}${'$'};
            create trigger fail_delete_audit before insert on audit_log
              for each row when (new.action = 'DELETE' and new.tree_id = '${f.tree}') execute function fail_delete_audit();
            """.trimIndent(),
        )
        try {
            val before = fingerprint(f.tree)
            for ((name, call) in calls) {
                val error = assertFails(name) { runBlocking { call() } }
                assertFalse(error is ConflictException, "$name: an audit failure must not be reported as a conflict")
                val cause = generateSequence(error) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()
                assertNotNull(cause, "$name: expected a PSQLException in the cause chain, got $error")
                assertEquals("P0001", cause.sqlState, name)
                assertEquals(TRIGGER_MESSAGE, cause.serverErrorMessage?.message, name)
            }
            assertEquals(before, fingerprint(f.tree))
        } finally {
            execute("drop trigger fail_delete_audit on audit_log; drop function fail_delete_audit();")
        }
        runBlocking { for ((_, call) in calls) call() }
        // A referenced record still fails as a conflict, not as a database error.
        assertFailsWith<ConflictException> { runBlocking { PersonService.deletePerson(tree, PersonId(f.father.id), owner, v) } }
    }

    // --- Authorization and input --------------------------------------------------------

    @Test
    fun `a non-owner gets 403 for every delete before the path ID or version is checked`() = api {
        val f = family()
        val ownerOnly = "Only the tree owner can make changes to tree ${f.tree}"
        val notMember = "Not a member of tree ${f.tree}"
        val before = fingerprint(f.tree)
        for (t in deletions(f)) {
            val malformedPath = t.path.substringBeforeLast('/') + "/not-a-uuid"
            for ((p, version) in listOf(t.path to "1", t.path to null, t.path to "abc", malformedPath to "0")) {
                assertError(client.delete(p, Actor.RELATIVE, version), HttpStatusCode.Forbidden, ownerOnly, "relative ${t.name} $p $version")
            }
            assertError(client.delete(t.path, Actor.INVITEE, "1"), HttpStatusCode.Forbidden, notMember, "invitee ${t.name}")
            assertError(client.delete(t.path, Actor.OTHER_OWNER, "1"), HttpStatusCode.Forbidden, notMember, "other owner ${t.name}")
        }
        assertEquals(before, fingerprint(f.tree))
    }

    @Test
    fun `the version parameter, path IDs and missing records are validated and write nothing`() = api {
        val f = family()
        val t = path(f.tree)
        val lone = client.person(f.tree, Actor.OWNER, "Validated")
        val p = "$t/people/${lone.id}"
        val missing = UUID.randomUUID().toString()
        val before = fingerprint(f.tree)
        for (version in listOf(null, "", "0", "-1", "+1", "01", "1.5", "abc", "1e3", "9223372036854775808", "1&version=1")) {
            assertError(client.delete(p, Actor.OWNER, version), HttpStatusCode.BadRequest, VERSION_PARAM_MESSAGE, "version '$version'")
        }
        data class Case(val name: String, val path: String, val status: HttpStatusCode, val message: String)
        val cases = listOf(
            Case("person path", "$t/people/not-a-uuid", HttpStatusCode.BadRequest, "personId must be a UUID"),
            Case("union path", "$t/unions/not-a-uuid", HttpStatusCode.BadRequest, "unionId must be a UUID"),
            Case("link union path", "$t/unions/not-a-uuid/children/${f.child.id}", HttpStatusCode.BadRequest, "unionId must be a UUID"),
            Case("link child path", "$t/unions/${f.union.id}/children/not-a-uuid", HttpStatusCode.BadRequest, "childId must be a UUID"),
            Case("event path", "$t/events/not-a-uuid", HttpStatusCode.BadRequest, "eventId must be a UUID"),
            Case("place path", "$t/places/not-a-uuid", HttpStatusCode.BadRequest, "placeId must be a UUID"),
            Case("source path", "$t/sources/not-a-uuid", HttpStatusCode.BadRequest, "sourceId must be a UUID"),
            Case("missing person", "$t/people/$missing", HttpStatusCode.NotFound, "No person with id $missing"),
            Case("missing union", "$t/unions/$missing", HttpStatusCode.NotFound, "No union with id $missing"),
            Case("missing event", "$t/events/$missing", HttpStatusCode.NotFound, "No event with id $missing"),
            Case("missing place", "$t/places/$missing", HttpStatusCode.NotFound, "No place with id $missing"),
            Case("missing source", "$t/sources/$missing", HttpStatusCode.NotFound, "No source with id $missing"),
            Case(
                "unlinked child", "$t/unions/${f.union.id}/children/${f.father.id}", HttpStatusCode.NotFound,
                "No child ${f.father.id} in union ${f.union.id}",
            ),
        )
        for (case in cases) assertError(client.delete(case.path, Actor.OWNER, "1"), case.status, case.message, case.name)
        assertEquals(before, fingerprint(f.tree))
    }
}
