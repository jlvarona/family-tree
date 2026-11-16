package com.moxietech.family_tree.server.sources

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.api.CreateCitationRequest
import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.api.CreateSourceRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.SourceDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.api.UpdatePersonRequest
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.TreeId
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * FT-002 citation routes over HTTP, through the production [module] and token
 * verifier, on a disposable PostgreSQL 16 container migrated by Flyway.
 *
 * Contract: `POST /api/v1/trees/{treeId}/citations` (owner only) → 201 `CitationDto`,
 * audited in the same transaction; `GET …/{people|events|unions}/{id}/citations`
 * (active members) lists one target's citations. Foreign and missing sources and
 * targets are the same 404. A non-owner member gets no citations for a hidden
 * (living) person, a union with a hidden partner, or an event of either.
 *
 * Actors: OWNER owns tree A; RELATIVE is an active non-owner member of tree A
 * (living people are hidden from them); INVITEE has only an invitation;
 * OTHER_OWNER owns tree B. "Nothing written" is a fingerprint of every tenant row.
 */
class CitationRoutesTest {

    private enum class Actor(val subject: String, val email: String) {
        OWNER("ft002-owner", "ft002-owner@example.com"),
        OTHER_OWNER("ft002-other", "ft002-other@example.com"),
        RELATIVE("ft002-relative", "ft002-relative@example.com"),
        INVITEE("ft002-invitee", "ft002-invitee@example.com"),
    }

    /** Built by the owner; only [living] is living, so RELATIVE can't see them. */
    private data class Family(
        val tree: String,
        val father: PersonDto,
        val mother: PersonDto,
        val living: PersonDto,
        /** father + mother, both deceased. */
        val openUnion: UnionDto,
        /** father + [living], dated, so FT-066 hides its dates from RELATIVE. */
        val privateUnion: UnionDto,
        val source: SourceDto,
        val fatherEvent: EventDto,
        val livingEvent: EventDto,
        val openUnionEvent: EventDto,
        val privateUnionEvent: EventDto,
    )

    /** One citable record, its listing route, and whether RELATIVE may see its citations. */
    private data class Cited(val name: String, val targetType: String, val id: String, val collection: String, val visible: Boolean) {
        fun path(tree: String) = "/api/v1/trees/$tree/$collection/$id/citations"
    }

    /** A delete and a citation create aimed at the same row (the target, or the citation's source). */
    private data class Race(
        val name: String,
        val table: String,
        val entityType: String,
        val entityId: String,
        val deletePath: String,
        val version: Long,
        val request: CreateCitationRequest,
        val notFound: String,
    ) {
        val referenced get() = "$entityType $entityId is still referenced by other records; remove those references first"
    }

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val ISSUER = "http://keycloak.test/realms/family-tree"
        private const val TRIGGER_MESSAGE = "FT-002 test-only trigger rejected the citation audit"
        private val json = Json { ignoreUnknownKeys = true }
        private val tables =
            listOf("person", "union_record", "union_child", "event", "place", "source", "citation", "membership", "audit_log")

        private lateinit var dataSource: HikariDataSource
        private lateinit var jwks: TestJwks
        private lateinit var accounts: Map<Actor, AccountDto>
        private lateinit var treeA: String
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
                treeA = client.createTree(token(Actor.OWNER), "FT-002 citations A").id
                val treeB = client.createTree(token(Actor.OTHER_OWNER), "FT-002 citations B").id
                MembershipRepository.insert(
                    Uuid.parse(treeA), Uuid.parse(accounts.getValue(Actor.RELATIVE).id), personId = null, status = MembershipStatus.ACTIVE,
                )
                MembershipRepository.insert(
                    Uuid.parse(treeA), Uuid.parse(accounts.getValue(Actor.INVITEE).id), personId = null, status = MembershipStatus.INVITED,
                )
                foreign = client.newFamily(treeB, Actor.OTHER_OWNER)
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

        private fun citations(treeId: String) = "${path(treeId)}/citations"

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

        private suspend fun HttpClient.get(path: String, actor: Actor) = send(HttpMethod.Get, path, actor)

        private suspend fun HttpClient.post(path: String, actor: Actor, body: String) = send(HttpMethod.Post, path, actor, body)

        private suspend fun HttpClient.put(path: String, actor: Actor, body: String) = send(HttpMethod.Put, path, actor, body)

        private suspend fun HttpClient.delete(path: String, actor: Actor, version: Long) =
            send(HttpMethod.Delete, "$path?version=$version", actor)

        private suspend fun HttpClient.person(tree: String, owner: Actor, given: String, living: Boolean = false): PersonDto =
            post("${path(tree)}/people", owner, encode(CreatePersonRequest(givenNames = given, sex = "UNKNOWN", isLiving = living)))
                .decoded<CreatePersonResponse>(HttpStatusCode.Created).person

        private suspend fun HttpClient.union(
            tree: String,
            owner: Actor,
            partner1: String,
            partner2: String? = null,
            type: String = "PARTNERS",
            startDate: String? = null,
        ): UnionDto =
            post("${path(tree)}/unions", owner, encode(CreateUnionRequest(partner1, partner2, type = type, startDate = startDate)))
                .decoded<UnionDto>(HttpStatusCode.Created)

        private suspend fun HttpClient.event(tree: String, owner: Actor, personId: String? = null, unionId: String? = null): EventDto {
            val type = if (personId != null) "BIRTH" else "OTHER"
            return post("${path(tree)}/events", owner, encode(CreateEventRequest(personId = personId, unionId = unionId, type = type, date = "1950")))
                .decoded<EventDto>(HttpStatusCode.Created)
        }

        private suspend fun HttpClient.source(tree: String, owner: Actor, title: String): SourceDto =
            post("${path(tree)}/sources", owner, encode(CreateSourceRequest(title = title, type = "CHURCH_RECORD")))
                .decoded<SourceDto>(HttpStatusCode.Created)

        private suspend fun HttpClient.newFamily(tree: String, owner: Actor): Family {
            val father = person(tree, owner, "Mateo")
            val mother = person(tree, owner, "Julia")
            val living = person(tree, owner, "Lucia", living = true)
            val openUnion = union(tree, owner, father.id, mother.id, type = "MARRIED", startDate = "1970")
            val privateUnion = union(tree, owner, father.id, living.id, startDate = "2001")
            return Family(
                tree = tree,
                father = father,
                mother = mother,
                living = living,
                openUnion = openUnion,
                privateUnion = privateUnion,
                source = source(tree, owner, "Parish register"),
                fatherEvent = event(tree, owner, personId = father.id),
                livingEvent = event(tree, owner, personId = living.id),
                openUnionEvent = event(tree, owner, unionId = openUnion.id),
                privateUnionEvent = event(tree, owner, unionId = privateUnion.id),
            )
        }
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(settings()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.family() = client.newFamily(treeA, Actor.OWNER)

    private fun Family.targets() = listOf(
        Cited("deceased person", "PERSON", father.id, "people", visible = true),
        Cited("living person", "PERSON", living.id, "people", visible = false),
        Cited("union of deceased partners", "UNION", openUnion.id, "unions", visible = true),
        Cited("union with a living partner", "UNION", privateUnion.id, "unions", visible = false),
        Cited("deceased person's event", "EVENT", fatherEvent.id, "events", visible = true),
        Cited("living person's event", "EVENT", livingEvent.id, "events", visible = false),
        Cited("event of a deceased couple's union", "EVENT", openUnionEvent.id, "events", visible = true),
        Cited("event of a union with a living partner", "EVENT", privateUnionEvent.id, "events", visible = false),
    )

    private suspend fun ApplicationTestBuilder.cite(tree: String, request: CreateCitationRequest): CitationDto =
        client.post(citations(tree), Actor.OWNER, encode(request)).decoded(HttpStatusCode.Created)

    private fun fingerprint(treeId: String): Map<String, String?> = tables.associateWith { table ->
        dataSource.query("select string_agg(t::text, E'\\n' order by t::text) from $table t where tree_id = ?", uuid(treeId))
    }

    private fun count(sql: String, vararg args: Any?): Long = dataSource.query(sql, *args)!!.toLong()

    private fun citationAudits(tree: String): Long =
        count("select count(*) from audit_log where tree_id = ? and entity_type = 'Citation'", uuid(tree))

    private fun deleteAudits(tree: String, entityType: String, entityId: String): Long = count(
        "select count(*) from audit_log where tree_id = ? and entity_type = ? and entity_id = ? and action = 'DELETE'",
        uuid(tree), entityType, entityId,
    )

    private fun execute(sql: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, message: String, context: String) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, "$context: $body")
        assertEquals(message, json.decodeFromString<ApiErrorResponse>(body).message, context)
        assertFalse("Exception" in body || "SQL" in body || "fkey" in body, "$context exposed internals: $body")
    }

    /** Waits, at most 10 s, until another session waits on a lock in a statement matching `%[pattern]%`. */
    private suspend fun awaitBlocked(pattern: String) = withTimeout(10_000) {
        val sql = """
            select count(*) from pg_stat_activity
            where pid <> pg_backend_pid() and wait_event_type = 'Lock' and query ilike ?
        """.trimIndent()
        while (count(sql, "%$pattern%") < 1) delay(25)
    }

    // --- Create and list ----------------------------------------------------------------

    @Test
    fun `the owner cites a person, an event and a union, each audited once with the created citation`() = api {
        val f = family()
        val owner = accounts.getValue(Actor.OWNER).id
        for (t in f.targets()) {
            // Upper-case hex is the same ID (FT-070): stored and returned in canonical form.
            val request = CreateCitationRequest(f.source.id, t.targetType, t.id.uppercase(), page = "f. 12v", confidence = 3, note = t.name)
            val created = cite(f.tree, request)
            assertEquals(CitationDto(created.id, f.source.id, t.targetType, t.id, "f. 12v", 3, t.name, null), created, t.name)
            assertEquals(listOf(created), client.get(t.path(f.tree), Actor.OWNER).decoded<List<CitationDto>>(), t.name)

            dataSource.connection.use { c ->
                c.prepareStatement(
                    "select actor_account_id, action, before_json, after_json from audit_log " +
                        "where tree_id = ? and entity_type = 'Citation' and entity_id = ?",
                ).use { st ->
                    st.setObject(1, uuid(f.tree)); st.setString(2, created.id)
                    st.executeQuery().use { rs ->
                        assertTrue(rs.next(), "${t.name}: no audit entry")
                        assertEquals(owner, rs.getString("actor_account_id"), t.name)
                        assertEquals("CREATE", rs.getString("action"), t.name)
                        assertEquals(null, rs.getString("before_json"), t.name)
                        assertEquals(AuditJson.encodeToJsonElement(created), json.parseToJsonElement(rs.getString("after_json")), t.name)
                        assertFalse(rs.next(), "${t.name}: exactly one audit entry")
                    }
                }
            }
        }
    }

    @Test
    fun `citations on a hidden person, a union with a hidden partner, or their events are withheld from other members`() = api {
        val f = family()
        val created = f.targets().associateWith { t ->
            cite(
                f.tree,
                CreateCitationRequest(
                    f.source.id, t.targetType, t.id,
                    page = "page-${UUID.randomUUID()}", confidence = 2, note = "note-${UUID.randomUUID()}",
                ),
            )
        }
        for ((t, citation) in created) {
            assertEquals(listOf(citation), client.get(t.path(f.tree), Actor.OWNER).decoded<List<CitationDto>>(), "owner, ${t.name}")
            val seen = client.get(t.path(f.tree), Actor.RELATIVE).decoded<List<CitationDto>>()
            assertEquals(if (t.visible) listOf(citation) else emptyList(), seen, "relative, ${t.name}")
        }

        // No other route the relative can call returns a withheld citation or its details.
        val t = path(f.tree)
        val people = listOf(f.father, f.mother, f.living)
        val routes = listOf("$t/people", "$t/unions", "$t/graph", "$t/sources", "$t/places") +
            people.flatMap { p -> listOf("$t/people/${p.id}", "$t/people/${p.id}/events", "$t/people/${p.id}/ancestors", "$t/people/${p.id}/descendants") }
        val withheld = created.filterKeys { !it.visible }.values
        for (route in routes) {
            val response = client.get(route, Actor.RELATIVE)
            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.OK, response.status, "$route: $body")
            for (c in withheld) {
                for (secret in listOf(c.id, c.page!!, c.note!!)) assertFalse(secret in body, "$route leaked $secret")
            }
        }

        // FT-066 projections are unchanged: the union with a living partner keeps its type
        // and links without dates, and the living partner is a placeholder.
        val graph = client.get("$t/graph", Actor.RELATIVE).decoded<TreeGraphDto>()
        assertEquals(f.privateUnion.copy(startDate = null, endDate = null), graph.unions.single { it.id == f.privateUnion.id })
        assertEquals(f.openUnion, graph.unions.single { it.id == f.openUnion.id })
        assertEquals("Private", graph.people.single { it.id == f.living.id }.givenNames)
        assertEquals(
            emptyList(),
            client.get("$t/people/${f.living.id}/events", Actor.RELATIVE).decoded<List<EventDto>>(),
        )

        // The same ViewerPolicy decides: once the person is no longer living, every citation shows.
        client.put(
            "$t/people/${f.living.id}", Actor.OWNER,
            encode(UpdatePersonRequest(givenNames = f.living.givenNames, sex = "UNKNOWN", isLiving = false, version = f.living.version)),
        ).decoded<PersonDto>()
        for ((target, citation) in created) {
            assertEquals(listOf(citation), client.get(target.path(f.tree), Actor.RELATIVE).decoded<List<CitationDto>>(), "after, ${target.name}")
        }
    }

    // --- Tenant isolation and access ----------------------------------------------------

    @Test
    fun `another tree's sources, targets and citations read like missing IDs and nothing is written`() = api {
        val f = family()
        val g = foreign
        val missing = UUID.randomUUID().toString()
        val beforeA = fingerprint(f.tree)
        val beforeB = fingerprint(g.tree)

        fun citeRequest(source: String, type: String, target: String) = CreateCitationRequest(source, type, target, confidence = 1)
        val creates = listOf(
            citeRequest(g.source.id, "PERSON", f.father.id) to "No source with id ${g.source.id}",
            citeRequest(missing, "PERSON", f.father.id) to "No source with id $missing",
            citeRequest(f.source.id, "PERSON", g.father.id) to "No person with id ${g.father.id}",
            citeRequest(f.source.id, "PERSON", missing) to "No person with id $missing",
            citeRequest(f.source.id, "EVENT", g.fatherEvent.id) to "No event with id ${g.fatherEvent.id}",
            citeRequest(f.source.id, "EVENT", missing) to "No event with id $missing",
            citeRequest(f.source.id, "UNION", g.openUnion.id) to "No union with id ${g.openUnion.id}",
            citeRequest(f.source.id, "UNION", missing) to "No union with id $missing",
            // A tree A event ID named as a person is not a person.
            citeRequest(f.source.id, "PERSON", f.fatherEvent.id) to "No person with id ${f.fatherEvent.id}",
        )
        for ((body, message) in creates) {
            assertError(client.post(citations(f.tree), Actor.OWNER, encode(body)), HttpStatusCode.NotFound, message, message)
        }

        val reads = listOf(
            "people" to "person" to listOf(g.father.id, missing),
            "events" to "event" to listOf(g.fatherEvent.id, missing),
            "unions" to "union" to listOf(g.openUnion.id, missing),
        )
        for ((names, ids) in reads) {
            val (collection, label) = names
            for (id in ids) {
                assertError(
                    client.get("${path(f.tree)}/$collection/$id/citations", Actor.OWNER), HttpStatusCode.NotFound,
                    "No $label with id $id", "$collection $id",
                )
            }
        }

        val notMember = "Not a member of tree ${f.tree}"
        val valid = encode(citeRequest(f.source.id, "PERSON", f.father.id))
        for (actor in listOf(Actor.OTHER_OWNER, Actor.INVITEE)) {
            assertError(client.post(citations(f.tree), actor, valid), HttpStatusCode.Forbidden, notMember, "$actor create")
            for (target in f.targets()) {
                assertError(client.get(target.path(f.tree), actor), HttpStatusCode.Forbidden, notMember, "$actor ${target.name}")
            }
        }
        assertEquals(beforeA, fingerprint(f.tree))
        assertEquals(beforeB, fingerprint(g.tree))

        // Invalid data V2's preflight would reject: tree B's citation naming tree A's person.
        // It is never listed under tree A.
        val stray = UUID.randomUUID()
        execute(
            "insert into citation (id, tree_id, source_id, target_type, target_id, page, confidence) " +
                "values ('$stray', '${g.tree}', '${g.source.id}', 'PERSON', '${f.father.id}', 'stray', 1)",
        )
        try {
            val response = client.get("${path(f.tree)}/people/${f.father.id}/citations", Actor.OWNER)
            assertEquals(emptyList(), response.decoded<List<CitationDto>>())
        } finally {
            execute("delete from citation where id = '$stray'")
        }
    }

    @Test
    fun `only the owner may cite, and every input is validated before anything is written`() = api {
        val f = family()
        val url = citations(f.tree)
        val valid = CreateCitationRequest(f.source.id, "PERSON", f.father.id, confidence = 2)
        val before = fingerprint(f.tree)

        val ownerOnly = "Only the tree owner can make changes to tree ${f.tree}"
        for (body in listOf(encode(valid), "{not json", "{}")) {
            assertError(client.post(url, Actor.RELATIVE, body), HttpStatusCode.Forbidden, ownerOnly, "relative $body")
        }

        data class Case(val name: String, val body: String, val status: HttpStatusCode, val message: String)
        val bad = HttpStatusCode.BadRequest
        val cases = listOf(
            Case("malformed JSON", "{not json", bad, MALFORMED_BODY_MESSAGE),
            Case(
                "missing confidence",
                """{"sourceId":"${f.source.id}","targetType":"PERSON","targetId":"${f.father.id}"}""", bad, MALFORMED_BODY_MESSAGE,
            ),
            Case("malformed sourceId", encode(valid.copy(sourceId = "nope")), bad, "sourceId must be a UUID"),
            Case("lower-case target type", encode(valid.copy(targetType = "person")), bad, "Unknown citation target type 'person'"),
            Case("unknown target type", encode(valid.copy(targetType = "PLACE")), bad, "Unknown citation target type 'PLACE'"),
            Case("malformed targetId", encode(valid.copy(targetId = "nope")), bad, "targetId must be a UUID"),
            Case("confidence 4", encode(valid.copy(confidence = 4)), bad, CITATION_CONFIDENCE_MESSAGE),
            Case("confidence -1", encode(valid.copy(confidence = -1)), bad, CITATION_CONFIDENCE_MESSAGE),
            Case("media ID", encode(valid.copy(mediaId = UUID.randomUUID().toString())), bad, CITATION_MEDIA_MESSAGE),
        )
        for (case in cases) assertError(client.post(url, Actor.OWNER, case.body), case.status, case.message, case.name)

        val t = path(f.tree)
        for ((collection, field) in listOf("people" to "personId", "events" to "eventId", "unions" to "unionId")) {
            assertError(
                client.get("$t/$collection/not-a-uuid/citations", Actor.OWNER), bad, "$field must be a UUID", "$collection path",
            )
            assertError(
                client.get("$t/$collection/not-a-uuid/citations", Actor.RELATIVE), bad, "$field must be a UUID", "$collection path",
            )
        }
        assertEquals(before, fingerprint(f.tree))
    }

    // --- Interaction with deletes -------------------------------------------------------

    @Test
    fun `a citation created through the API blocks deleting its source and its target`() = api {
        val f = family()
        val loner = client.person(f.tree, Actor.OWNER, "Cited")
        val bare = client.union(f.tree, Actor.OWNER, f.mother.id)
        val event = client.event(f.tree, Actor.OWNER, personId = f.mother.id)
        val source = client.source(f.tree, Actor.OWNER, "Census")
        for ((type, id) in listOf("PERSON" to loner.id, "UNION" to bare.id, "EVENT" to event.id)) {
            cite(f.tree, CreateCitationRequest(source.id, type, id, confidence = 1))
        }
        val t = path(f.tree)
        val deletes = listOf(
            Triple("$t/people/${loner.id}", loner.version, "Person ${loner.id}"),
            Triple("$t/unions/${bare.id}", bare.version, "Union ${bare.id}"),
            Triple("$t/events/${event.id}", event.version, "Event ${event.id}"),
            Triple("$t/sources/${source.id}", source.version, "Source ${source.id}"),
        )
        val before = fingerprint(f.tree)
        for ((p, version, label) in deletes) {
            assertError(
                client.delete(p, Actor.OWNER, version), HttpStatusCode.Conflict,
                "$label is still referenced by other records; remove those references first", label,
            )
        }
        assertEquals(before, fingerprint(f.tree))
    }

    /** Fresh, otherwise unreferenced rows for one delete-versus-citation race of each kind. */
    private suspend fun ApplicationTestBuilder.races(f: Family, label: String): List<Race> {
        val t = path(f.tree)
        val person = client.person(f.tree, Actor.OWNER, "Raced $label")
        val partner = client.person(f.tree, Actor.OWNER, "Partner $label")
        val union = client.union(f.tree, Actor.OWNER, partner.id)
        val event = client.event(f.tree, Actor.OWNER, personId = f.mother.id)
        val source = client.source(f.tree, Actor.OWNER, "Raced source $label")
        fun cites(type: String, id: String, sourceId: String = f.source.id) = CreateCitationRequest(sourceId, type, id, confidence = 2)
        return listOf(
            Race("person", "person", "Person", person.id, "$t/people/${person.id}", person.version,
                cites("PERSON", person.id), "No person with id ${person.id}"),
            Race("union", "union_record", "Union", union.id, "$t/unions/${union.id}", union.version,
                cites("UNION", union.id), "No union with id ${union.id}"),
            Race("event", "event", "Event", event.id, "$t/events/${event.id}", event.version,
                cites("EVENT", event.id), "No event with id ${event.id}"),
            Race("source", "source", "Source", source.id, "$t/sources/${source.id}", source.version,
                cites("PERSON", f.father.id, sourceId = source.id), "No source with id ${source.id}"),
        )
    }

    /**
     * Citation first: the create locks its source and target `FOR KEY SHARE` and is then
     * parked on its INSERT by a test-held `SHARE` lock on `citation`. The delete runs
     * its checks, finds nothing, and its DELETE waits on the create's row lock. Once
     * released, the create commits (201) and the delete sees the committed citation,
     * through its post-DELETE check or, for a source, the foreign key: 409, nothing
     * deleted, nothing audited for it.
     */
    @Test
    fun `a citation that locks first wins over a concurrent delete of its target or source`() = api {
        val f = family()
        for (race in races(f, "citation first")) {
            val (create, delete) = coroutineScope {
                val blocker = dataSource.connection
                try {
                    blocker.autoCommit = false
                    blocker.createStatement().use { it.execute("lock table citation in share mode") }
                    val create = async { client.post(citations(f.tree), Actor.OWNER, encode(race.request)) }
                    awaitBlocked("insert into%citation")
                    val delete = async { client.delete(race.deletePath, Actor.OWNER, race.version) }
                    awaitBlocked("delete from%${race.table}")
                    blocker.commit()
                    withTimeout(10_000) { create.await() to delete.await() }
                } finally {
                    runCatching { blocker.rollback() }
                    blocker.close()
                }
            }
            val citation = create.decoded<CitationDto>(HttpStatusCode.Created)
            assertError(delete, HttpStatusCode.Conflict, race.referenced, race.name)
            assertEquals(1, count("select count(*) from ${race.table} where id = ?", uuid(race.entityId)), race.name)
            assertEquals(1, count("select count(*) from citation where id = ?", uuid(citation.id)), race.name)
            assertEquals(
                1,
                count("select count(*) from audit_log where entity_type = 'Citation' and entity_id = ? and action = 'CREATE'", citation.id),
                race.name,
            )
            assertEquals(0, deleteAudits(f.tree, race.entityType, race.entityId), race.name)
        }
    }

    /**
     * Delete first: the delete's DELETE runs and the delete is parked on its audit insert
     * by a test-held `SHARE` lock on `audit_log`, holding the row lock. The create's
     * `FOR KEY SHARE` waits on it; once the delete commits (204), the lock finds no row
     * and the create is the generic 404 with no citation and no audit entry.
     */
    @Test
    fun `a delete that locks first wins over a concurrent citation of its row`() = api {
        val f = family()
        for (race in races(f, "delete first")) {
            val citationAuditsBefore = citationAudits(f.tree)
            val citationsBefore = count("select count(*) from citation where tree_id = ?", uuid(f.tree))
            val (delete, create) = coroutineScope {
                val blocker = dataSource.connection
                try {
                    blocker.autoCommit = false
                    blocker.createStatement().use { it.execute("lock table audit_log in share mode") }
                    val delete = async { client.delete(race.deletePath, Actor.OWNER, race.version) }
                    awaitBlocked("insert into%audit_log")
                    val create = async { client.post(citations(f.tree), Actor.OWNER, encode(race.request)) }
                    awaitBlocked("for key share")
                    blocker.commit()
                    withTimeout(10_000) { delete.await() to create.await() }
                } finally {
                    runCatching { blocker.rollback() }
                    blocker.close()
                }
            }
            assertEquals(HttpStatusCode.NoContent, delete.status, "${race.name}: ${delete.bodyAsText()}")
            assertError(create, HttpStatusCode.NotFound, race.notFound, race.name)
            assertEquals(0, count("select count(*) from ${race.table} where id = ?", uuid(race.entityId)), race.name)
            assertEquals(citationsBefore, count("select count(*) from citation where tree_id = ?", uuid(f.tree)), race.name)
            assertEquals(citationAuditsBefore, citationAudits(f.tree), race.name)
            assertEquals(1, deleteAudits(f.tree, race.entityType, race.entityId), race.name)
        }
    }

    // --- Audit atomicity ----------------------------------------------------------------

    @Test
    fun `a failed audit insert rolls back the citation`() {
        lateinit var f: Family
        testApplication {
            application { module(settings()) }
            f = family()
        }
        val tree = TreeId(f.tree)
        val owner = AccountId(accounts.getValue(Actor.OWNER).id)
        val request = CreateCitationRequest(f.source.id, "PERSON", f.father.id, page = "rolled back", confidence = 0)
        execute(
            """
            create function fail_citation_audit() returns trigger language plpgsql as
            ${'$'}${'$'} begin raise exception '$TRIGGER_MESSAGE'; end ${'$'}${'$'};
            create trigger fail_citation_audit before insert on audit_log
              for each row when (new.entity_type = 'Citation' and new.tree_id = '${f.tree}') execute function fail_citation_audit();
            """.trimIndent(),
        )
        try {
            val before = fingerprint(f.tree)
            val error = assertFails { runBlocking { CitationService.createCitation(tree, owner, request) } }
            val cause = generateSequence(error) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()
            assertNotNull(cause, "expected a PSQLException in the cause chain, got $error")
            assertEquals("P0001", cause.sqlState)
            assertEquals(TRIGGER_MESSAGE, cause.serverErrorMessage?.message)
            assertEquals(before, fingerprint(f.tree))
        } finally {
            execute("drop trigger fail_citation_audit on audit_log; drop function fail_citation_audit();")
        }
        val created = runBlocking { CitationService.createCitation(tree, owner, request) }
        assertEquals(1, count("select count(*) from citation where id = ?", uuid(created.id)))
    }
}
