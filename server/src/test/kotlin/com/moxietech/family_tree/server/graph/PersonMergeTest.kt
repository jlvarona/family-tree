package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.AccountDto
import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.api.CreateCitationRequest
import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.api.CreatePlaceRequest
import com.moxietech.family_tree.api.CreateSourceRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.MergePeopleRequest
import com.moxietech.family_tree.api.MergePeopleResponse
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.PlaceDto
import com.moxietech.family_tree.api.SourceDto
import com.moxietech.family_tree.api.UnionDto
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.postgresql.util.PSQLException
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * FT-007 person merge over HTTP, through the production [module] and token verifier,
 * on a disposable PostgreSQL 16 container migrated by Flyway.
 *
 * Contract: `POST /api/v1/trees/{treeId}/people/merge` (owner only) with both IDs, both
 * reviewed versions and a choice for every differing profile field → 200
 * `MergePeopleResponse`. Every reference to the retired person moves to the survivor
 * keeping its ID (versions advanced where the row has one), the retired person is
 * deleted, and the decision plus every changed row is audited in the same transaction.
 *
 * Actors: OWNER owns tree A; RELATIVE is an active non-owner member of tree A (living
 * people are hidden from them); INVITEE has only an invitation; OTHER_OWNER owns tree
 * B. "Nothing written" is a fingerprint of every tenant row, audit entries included.
 */
class PersonMergeTest {

    private enum class Actor(val subject: String, val email: String) {
        OWNER("ft007-owner", "ft007-owner@example.com"),
        OTHER_OWNER("ft007-other", "ft007-other@example.com"),
        RELATIVE("ft007-relative", "ft007-relative@example.com"),
        INVITEE("ft007-invitee", "ft007-invitee@example.com"),
    }

    /** A record and its duplicate, entered twice as the same couple's child, with everything a person can be referenced by. */
    private data class Family(
        val tree: String,
        val mum: PersonDto,
        val dad: PersonDto,
        /** The survivor: deceased "Ana", paternal surname "Romero", no maternal surname. */
        val ana: PersonDto,
        /** The duplicate: living "Ana María", same paternal surname, maternal "Vidal", a bio. */
        val anaDup: PersonDto,
        val spouse: PersonDto,
        val grandchild: PersonDto,
        /** mum + dad, children ana and anaDup, both BIRTH. */
        val parents: UnionDto,
        /** anaDup + spouse, dated, child grandchild. */
        val marriage: UnionDto,
        val place: PlaceDto,
        val anaDupBirth: EventDto,
        val source: SourceDto,
        val anaDupCitation: CitationDto,
    )

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val ISSUER = "http://keycloak.test/realms/family-tree"
        private const val TRIGGER_MESSAGE = "FT-007 test-only trigger rejected the merge audit"
        private val json = Json { ignoreUnknownKeys = true }
        private val tables =
            listOf("person", "union_record", "union_child", "event", "place", "source", "citation", "membership", "audit_log")

        private lateinit var dataSource: HikariDataSource
        private lateinit var jwks: TestJwks
        private lateinit var accounts: Map<Actor, AccountDto>
        private lateinit var treeA: String
        private lateinit var treeB: String

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
                treeA = client.createTree(token(Actor.OWNER), "FT-007 merge A").id
                treeB = client.createTree(token(Actor.OTHER_OWNER), "FT-007 merge B").id
                MembershipRepository.insert(
                    Uuid.parse(treeA), Uuid.parse(accounts.getValue(Actor.RELATIVE).id), personId = null, status = MembershipStatus.ACTIVE,
                )
                MembershipRepository.insert(
                    Uuid.parse(treeA), Uuid.parse(accounts.getValue(Actor.INVITEE).id), personId = null, status = MembershipStatus.INVITED,
                )
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

        private fun mergePath(treeId: String) = "${path(treeId)}/people/merge"

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

        private suspend fun HttpClient.person(
            tree: String,
            owner: Actor,
            given: String,
            living: Boolean = false,
            paternal: String? = null,
            maternal: String? = null,
            sex: String = "UNKNOWN",
            bio: String? = null,
        ): PersonDto = post(
            "${path(tree)}/people",
            owner,
            encode(CreatePersonRequest(given, paternal, maternal, sex, living, bio)),
        ).decoded<CreatePersonResponse>(HttpStatusCode.Created).person

        private suspend fun HttpClient.union(
            tree: String,
            owner: Actor,
            partner1: String,
            partner2: String?,
            startDate: String? = null,
        ): UnionDto = post("${path(tree)}/unions", owner, encode(CreateUnionRequest(partner1, partner2, type = "MARRIED", startDate = startDate)))
            .decoded(HttpStatusCode.Created)

        private suspend fun HttpClient.child(tree: String, owner: Actor, unionId: String, childId: String, relation: String = "BIRTH"): UnionDto =
            post("${path(tree)}/unions/$unionId/children", owner, encode(AddUnionChildRequest(childId, relation))).decoded(HttpStatusCode.Created)

        private suspend fun HttpClient.newFamily(tree: String, owner: Actor): Family {
            val mum = person(tree, owner, "Carmen")
            val dad = person(tree, owner, "Luis")
            val ana = person(tree, owner, "Ana", paternal = "Romero", sex = "FEMALE")
            val anaDup = person(tree, owner, "Ana María", living = true, paternal = "Romero", maternal = "Vidal", sex = "FEMALE", bio = "Teacher")
            val spouse = person(tree, owner, "Pablo")
            val grandchild = person(tree, owner, "Inés")
            var parents = union(tree, owner, mum.id, dad.id)
            child(tree, owner, parents.id, ana.id)
            parents = child(tree, owner, parents.id, anaDup.id)
            val marriage = child(tree, owner, union(tree, owner, anaDup.id, spouse.id, startDate = "1990").id, grandchild.id)
            val place = post("${path(tree)}/places", owner, encode(CreatePlaceRequest("Sevilla"))).decoded<PlaceDto>(HttpStatusCode.Created)
            val birth = post(
                "${path(tree)}/events", owner, encode(CreateEventRequest(personId = anaDup.id, type = "BIRTH", date = "1960", placeId = place.id)),
            ).decoded<EventDto>(HttpStatusCode.Created)
            val source = post("${path(tree)}/sources", owner, encode(CreateSourceRequest("Parish register", "CHURCH_RECORD")))
                .decoded<SourceDto>(HttpStatusCode.Created)
            val citation = post(
                "${path(tree)}/citations", owner, encode(CreateCitationRequest(source.id, "PERSON", anaDup.id, page = "f. 3", confidence = 2)),
            ).decoded<CitationDto>(HttpStatusCode.Created)
            return Family(tree, mum, dad, ana, anaDup, spouse, grandchild, parents, marriage, place, birth, source, citation)
        }

        /** Every field of [Family.ana] vs [Family.anaDup] differs except the paternal surname and sex. */
        private val ALL_CHOICES = mapOf(
            "GIVEN_NAMES" to "RETIRED",
            "SURNAME_MATERNAL" to "RETIRED",
            "IS_LIVING" to "SURVIVOR",
            "BIO" to "RETIRED",
        )
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(settings()) }
        block()
    }

    private suspend fun ApplicationTestBuilder.family(tree: String = treeA, owner: Actor = Actor.OWNER) = client.newFamily(tree, owner)

    private fun Family.request(
        survivor: PersonDto = ana,
        retired: PersonDto = anaDup,
        choices: Map<String, String> = ALL_CHOICES,
        survivorVersion: Long = survivor.version,
        retiredVersion: Long = retired.version,
    ) = MergePeopleRequest(survivor.id, survivorVersion, retired.id, retiredVersion, choices)

    private fun fingerprint(treeId: String): Map<String, String?> = tables.associateWith { table ->
        dataSource.query("select string_agg(t::text, E'\\n' order by t::text) from $table t where tree_id = ?", uuid(treeId))
    }

    private fun count(sql: String, vararg args: Any?): Long = dataSource.query(sql, *args)!!.toLong()

    private fun execute(sql: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private data class Audit(val entityType: String, val entityId: String, val action: String, val before: String?, val after: String?)

    private fun auditIds(tree: String): Set<String> = dataSource.connection.use { c ->
        c.prepareStatement("select id from audit_log where tree_id = ?").use { st ->
            st.setObject(1, uuid(tree))
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toSet() }
        }
    }

    private fun auditsSince(tree: String, known: Set<String>): List<Audit> = dataSource.connection.use { c ->
        c.prepareStatement("select id, entity_type, entity_id, action, before_json, after_json from audit_log where tree_id = ?").use { st ->
            st.setObject(1, uuid(tree))
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        if (rs.getString("id") in known) continue
                        add(Audit(rs.getString("entity_type"), rs.getString("entity_id"), rs.getString("action"), rs.getString("before_json"), rs.getString("after_json")))
                    }
                }
            }
        }
    }

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, message: String? = null, context: String) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, "$context: $body")
        if (message != null) assertEquals(message, json.decodeFromString<ApiErrorResponse>(body).message, context)
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

    private fun linkMember(actor: Actor, personId: String?) = execute(
        "update membership set person_id = ${personId?.let { "'$it'" } ?: "null"} " +
            "where tree_id = '$treeA' and account_id = '${accounts.getValue(actor).id}'",
    )

    // --- Normal workflow ----------------------------------------------------------------

    @Test
    fun `the owner merges a duplicate with chosen values and audited reference moves`() = api {
        val f = family()
        linkMember(Actor.RELATIVE, f.anaDup.id)
        val knownAudits = auditIds(f.tree)

        // Upper-case IDs are the same people (FT-070); they are parsed once.
        val request = f.request().copy(survivorId = f.ana.id.uppercase(), retiredId = f.anaDup.id.uppercase())
        val result = client.post(mergePath(f.tree), Actor.OWNER, encode(request)).decoded<MergePeopleResponse>()

        // The survivor keeps its ID and gets the chosen values; equal fields are kept.
        val survivor = result.survivor
        assertEquals(f.anaDup.id, result.retiredId)
        assertEquals(f.ana.id, survivor.id)
        assertEquals("Ana María", survivor.givenNames)
        assertEquals("Romero", survivor.surnamePaternal)
        assertEquals("Vidal", survivor.surnameMaternal)
        assertEquals("Ana María Romero Vidal", survivor.fullName)
        assertEquals("FEMALE", survivor.sex)
        assertFalse(survivor.isLiving, "living status: the survivor's value was chosen")
        assertEquals("Teacher", survivor.bio)
        assertEquals(f.ana.version + 1, survivor.version)
        assertEquals(survivor, client.get("${path(f.tree)}/people/${f.ana.id}", Actor.OWNER).decoded())

        // The duplicate no longer exists anywhere.
        assertError(client.get("${path(f.tree)}/people/${f.anaDup.id}", Actor.OWNER), HttpStatusCode.NotFound, "No person with id ${f.anaDup.id}", "retired")
        assertEquals(0, count("select count(*) from person where id = ?", uuid(f.anaDup.id)))

        // The parents' union now has one link for her (the identical duplicate link is gone).
        val unions = client.get("${path(f.tree)}/unions", Actor.OWNER).decoded<List<UnionDto>>().associateBy { it.id }
        val parents = unions.getValue(f.parents.id)
        assertEquals(listOf(f.ana.id), parents.children.map { it.childId }.filter { it == f.ana.id || it == f.anaDup.id })
        assertEquals(f.parents.version, parents.version, "the union row itself is unchanged")
        // Her marriage keeps its ID, type, dates and child, with the survivor as partner and a new version.
        val marriage = unions.getValue(f.marriage.id)
        assertEquals(f.marriage.copy(partner1Id = f.ana.id, version = f.marriage.version + 1), marriage)

        // Her event, citation and member link point at the survivor, with the same IDs.
        val events = client.get("${path(f.tree)}/people/${f.ana.id}/events", Actor.OWNER).decoded<List<EventDto>>()
        assertEquals(listOf(f.anaDupBirth.copy(personId = f.ana.id, version = f.anaDupBirth.version + 1)), events)
        val citations = client.get("${path(f.tree)}/people/${f.ana.id}/citations", Actor.OWNER).decoded<List<CitationDto>>()
        assertEquals(listOf(f.anaDupCitation.copy(targetId = f.ana.id)), citations)
        assertEquals(
            f.ana.id,
            dataSource.query(
                "select person_id from membership where tree_id = ? and account_id = ?",
                uuid(f.tree), uuid(accounts.getValue(Actor.RELATIVE).id),
            ),
        )

        // One entry for the decision and one per changed row, all in this transaction.
        val audits = auditsSince(f.tree, knownAudits)
        val byKey = audits.associateBy { Triple(it.entityType, it.entityId, it.action) }
        assertEquals(8, audits.size, audits.joinToString("\n"))
        val decision = byKey.getValue(Triple("PersonMerge", "${f.ana.id}:${f.anaDup.id}", "CREATE"))
        assertNull(decision.before)
        val decided = json.parseToJsonElement(decision.after!!) as JsonObject
        assertEquals(f.ana.id, decided.getValue("survivorId").jsonPrimitive.content)
        assertEquals(f.anaDup.version.toString(), decided.getValue("retiredVersion").jsonPrimitive.content)
        assertEquals(AuditJson.encodeToJsonElement(ALL_CHOICES), decided.getValue("choices"))
        assertEquals(
            AuditJson.encodeToJsonElement(survivor),
            json.parseToJsonElement(byKey.getValue(Triple("Person", f.ana.id, "UPDATE")).after!!),
        )
        assertEquals(
            AuditJson.encodeToJsonElement(f.anaDup),
            json.parseToJsonElement(byKey.getValue(Triple("Person", f.anaDup.id, "DELETE")).before!!),
        )
        assertNull(byKey.getValue(Triple("Person", f.anaDup.id, "DELETE")).after)
        assertEquals(
            AuditJson.encodeToJsonElement(marriage),
            json.parseToJsonElement(byKey.getValue(Triple("Union", f.marriage.id, "UPDATE")).after!!),
        )
        assertNotNull(byKey[Triple("UnionChild", "${f.parents.id}:${f.anaDup.id}", "DELETE")])
        assertEquals(
            AuditJson.encodeToJsonElement(events.single()),
            json.parseToJsonElement(byKey.getValue(Triple("Event", f.anaDupBirth.id, "UPDATE")).after!!),
        )
        assertEquals(
            AuditJson.encodeToJsonElement(citations.single()),
            json.parseToJsonElement(byKey.getValue(Triple("Citation", f.anaDupCitation.id, "UPDATE")).after!!),
        )
        val membership = byKey.getValue(Triple("Membership", "${f.tree}:${accounts.getValue(Actor.RELATIVE).id}", "UPDATE"))
        assertTrue(f.anaDup.id in membership.before!! && f.ana.id in membership.after!!)
    }

    @Test
    fun `a link only the duplicate had moves with its relation under a fresh version`() = api {
        val f = family()
        val guardian = client.person(f.tree, Actor.OWNER, "Rosa")
        val adoptive = client.union(f.tree, Actor.OWNER, guardian.id, null)
        val linked = client.child(f.tree, Actor.OWNER, adoptive.id, f.anaDup.id, relation = "ADOPTED")
        val oldVersion = linked.children.single().version

        client.post(mergePath(f.tree), Actor.OWNER, encode(f.request())).decoded<MergePeopleResponse>()

        val after = client.get("${path(f.tree)}/unions", Actor.OWNER).decoded<List<UnionDto>>().single { it.id == adoptive.id }
        val moved = after.children.single()
        assertEquals(f.ana.id, moved.childId)
        assertEquals("ADOPTED", moved.relation)
        assertTrue(moved.version > oldVersion, "child-link versions come from the V5 sequence")
        assertEquals(1, count(
            "select count(*) from audit_log where tree_id = ? and entity_type = 'UnionChild' and entity_id = ? and action = 'UPDATE'",
            uuid(f.tree), "${adoptive.id}:${f.anaDup.id}",
        ))
        // The old key is gone; a request with the old link's version can't touch the moved link.
        assertError(
            client.send(HttpMethod.Delete, "${path(f.tree)}/unions/${adoptive.id}/children/${f.anaDup.id}?version=$oldVersion", Actor.OWNER),
            HttpStatusCode.NotFound, context = "old link key",
        )
    }

    @Test
    fun `merging a living survivor keeps the member's privacy view and the union policy`() = api {
        val f = family()
        val choices = ALL_CHOICES + ("IS_LIVING" to "RETIRED")
        val survivor = client.post(mergePath(f.tree), Actor.OWNER, encode(f.request(choices = choices))).decoded<MergePeopleResponse>().survivor
        assertTrue(survivor.isLiving)

        val seen = client.get("${path(f.tree)}/people/${f.ana.id}", Actor.RELATIVE).decoded<PersonDto>()
        assertEquals("Private", seen.givenNames)
        assertNull(seen.bio)
        val marriage = client.get("${path(f.tree)}/unions", Actor.RELATIVE).decoded<List<UnionDto>>().single { it.id == f.marriage.id }
        assertEquals("MARRIED", marriage.type)
        assertEquals(f.ana.id, marriage.partner1Id)
        assertNull(marriage.startDate, "a union with a hidden partner loses its dates (FT-066)")
        assertEquals(emptyList(), client.get("${path(f.tree)}/people/${f.ana.id}/citations", Actor.RELATIVE).decoded<List<CitationDto>>())
    }

    // --- Authorization and tenant scope -------------------------------------------------

    @Test
    fun `only the tree owner may merge, whatever the body, and nothing is written`() = api {
        val f = family()
        val before = fingerprint(f.tree)
        for (body in listOf(encode(f.request()), "{", """{"survivorId":"x"}""")) {
            assertError(
                client.post(mergePath(f.tree), Actor.RELATIVE, body), HttpStatusCode.Forbidden,
                "Only the tree owner can make changes to tree ${f.tree}", "relative",
            )
            assertError(client.post(mergePath(f.tree), Actor.INVITEE, body), HttpStatusCode.Forbidden, "Not a member of tree ${f.tree}", "invitee")
            assertError(client.post(mergePath(f.tree), Actor.OTHER_OWNER, body), HttpStatusCode.Forbidden, "Not a member of tree ${f.tree}", "other owner")
        }
        assertEquals(before, fingerprint(f.tree))
    }

    @Test
    fun `people from another tree are the same 404 as missing ones and neither tree changes`() = api {
        val f = family()
        val other = family(treeB, Actor.OTHER_OWNER)
        val beforeA = fingerprint(treeA)
        val beforeB = fingerprint(treeB)
        val missing = Uuid.random().toString()

        assertError(
            client.post(mergePath(f.tree), Actor.OWNER, encode(f.request(retired = other.anaDup))),
            HttpStatusCode.NotFound, "No person with id ${other.anaDup.id}", "foreign retired",
        )
        assertError(
            client.post(mergePath(f.tree), Actor.OWNER, encode(f.request(survivor = other.ana))),
            HttpStatusCode.NotFound, "No person with id ${other.ana.id}", "foreign survivor",
        )
        assertError(
            client.post(mergePath(f.tree), Actor.OWNER, encode(f.request().copy(retiredId = missing))),
            HttpStatusCode.NotFound, "No person with id $missing", "missing",
        )
        // Tree B's own people in tree B's URL, by tree A's owner: not a member.
        assertError(client.post(mergePath(treeB), Actor.OWNER, encode(other.request())), HttpStatusCode.Forbidden, context = "tree B URL")
        assertEquals(beforeA, fingerprint(treeA))
        assertEquals(beforeB, fingerprint(treeB))
    }

    // --- Versions and validation --------------------------------------------------------

    @Test
    fun `a stale version of either person is a 409 that writes nothing, and the current versions merge`() = api {
        val f = family()
        val renamed = client.send(
            HttpMethod.Put, "${path(f.tree)}/people/${f.anaDup.id}", Actor.OWNER,
            """{"givenNames":"Ana María","surnamePaternal":"Romero","surnameMaternal":"Vidal","sex":"FEMALE","isLiving":true,"bio":"Teacher, retired","version":${f.anaDup.version}}""",
        ).decoded<PersonDto>()
        val before = fingerprint(f.tree)

        assertError(
            client.post(mergePath(f.tree), Actor.OWNER, encode(f.request())), HttpStatusCode.Conflict,
            "Person ${f.anaDup.id} has been changed since you read it; reload it and try again", "stale retired",
        )
        assertError(
            client.post(mergePath(f.tree), Actor.OWNER, encode(f.request(retired = renamed, survivorVersion = f.ana.version + 7))),
            HttpStatusCode.Conflict, "Person ${f.ana.id} has been changed since you read it; reload it and try again", "future survivor",
        )
        assertEquals(before, fingerprint(f.tree))

        val merged = client.post(mergePath(f.tree), Actor.OWNER, encode(f.request(retired = renamed))).decoded<MergePeopleResponse>()
        assertEquals("Teacher, retired", merged.survivor.bio)
    }

    @Test
    fun `choices must name exactly the differing fields and every input is checked, writing nothing`() = api {
        val f = family()
        val before = fingerprint(f.tree)
        val cases = listOf(
            "missing choice" to encode(f.request(choices = ALL_CHOICES - "BIO")),
            "blank-vs-value still needs a choice" to encode(f.request(choices = ALL_CHOICES - "SURNAME_MATERNAL")),
            "choice for an equal field" to encode(f.request(choices = ALL_CHOICES + ("SURNAME_PATERNAL" to "SURVIVOR"))),
            "unknown field" to encode(f.request(choices = ALL_CHOICES + ("NICKNAME" to "SURVIVOR"))),
            "unknown side" to encode(f.request(choices = ALL_CHOICES + ("BIO" to "BOTH"))),
            "same person" to encode(f.request(retired = f.ana)),
            "same person, two spellings" to encode(f.request(retired = f.ana).copy(retiredId = f.ana.id.uppercase())),
            "malformed survivor" to encode(f.request().copy(survivorId = "not-a-uuid")),
            "malformed retired" to encode(f.request().copy(retiredId = "")),
            "missing choices" to """{"survivorId":"${f.ana.id}","survivorVersion":1,"retiredId":"${f.anaDup.id}","retiredVersion":1}""",
            "undecodable" to "{",
        )
        for ((name, body) in cases) {
            val response = client.post(mergePath(f.tree), Actor.OWNER, body)
            assertError(response, HttpStatusCode.BadRequest, context = name)
            assertFalse("not-a-uuid" in response.bodyAsText() || "NICKNAME" in response.bodyAsText(), "$name echoed input")
        }
        assertError(client.post(mergePath(f.tree), Actor.OWNER, "{"), HttpStatusCode.BadRequest, MALFORMED_BODY_MESSAGE, "undecodable")
        assertEquals(before, fingerprint(f.tree))
    }

    // --- Relationship integrity ---------------------------------------------------------

    @Test
    fun `merges that would break the graph or an unambiguous link are 409s that write nothing`() = api {
        val f = family()
        val partnerOfDup = client.person(f.tree, Actor.OWNER, "Ana")
        val grandparent = client.person(f.tree, Actor.OWNER, "Ana")
        client.child(f.tree, Actor.OWNER, client.union(f.tree, Actor.OWNER, grandparent.id, null).id, f.mum.id)
        val stepSibling = client.person(f.tree, Actor.OWNER, "Ana")
        client.child(f.tree, Actor.OWNER, f.parents.id, stepSibling.id, relation = "STEP")
        val partners = client.union(f.tree, Actor.OWNER, f.anaDup.id, partnerOfDup.id)

        val cases = listOf(
            Triple("partners of each other", f.request(retired = partnerOfDup, choices = mergeChoices(f.anaDup, partnerOfDup)).copy(survivorId = f.anaDup.id, survivorVersion = f.anaDup.version), "both partners"),
            Triple("parent and child", MergePeopleRequest(f.mum.id, f.mum.version, f.ana.id, f.ana.version, mergeChoices(f.mum, f.ana)), "their own parent"),
            Triple("grandparent and grandchild", MergePeopleRequest(grandparent.id, grandparent.version, f.ana.id, f.ana.version, mergeChoices(grandparent, f.ana)), "their own ancestor"),
            Triple("siblings with different relations", MergePeopleRequest(f.ana.id, f.ana.version, stepSibling.id, stepSibling.version, mergeChoices(f.ana, stepSibling)), "different relations"),
        )
        assertEquals(f.anaDup.id, partners.partner1Id)
        val before = fingerprint(f.tree)
        for ((name, request, reason) in cases) {
            val response = client.post(mergePath(f.tree), Actor.OWNER, encode(request))
            assertError(response, HttpStatusCode.Conflict, context = name)
            assertTrue(reason in response.bodyAsText(), "$name: ${response.bodyAsText()}")
        }
        assertEquals(before, fingerprint(f.tree))

        linkMember(Actor.RELATIVE, f.ana.id)
        linkMember(Actor.INVITEE, f.anaDup.id)
        try {
            val linked = fingerprint(f.tree)
            val response = client.post(mergePath(f.tree), Actor.OWNER, encode(f.request()))
            assertError(response, HttpStatusCode.Conflict, context = "both linked to accounts")
            assertTrue("member accounts" in response.bodyAsText())
            assertEquals(linked, fingerprint(f.tree))
        } finally {
            linkMember(Actor.RELATIVE, null)
            linkMember(Actor.INVITEE, null)
        }
    }

    private fun mergeChoices(survivor: PersonDto, retired: PersonDto): Map<String, String> {
        val pairs = listOf(
            "GIVEN_NAMES" to (survivor.givenNames to retired.givenNames),
            "SURNAME_PATERNAL" to (survivor.surnamePaternal.orEmpty().trim() to retired.surnamePaternal.orEmpty().trim()),
            "SURNAME_MATERNAL" to (survivor.surnameMaternal.orEmpty().trim() to retired.surnameMaternal.orEmpty().trim()),
            "SEX" to (survivor.sex to retired.sex),
            "IS_LIVING" to (survivor.isLiving.toString() to retired.isLiving.toString()),
            "BIO" to (survivor.bio.orEmpty().trim() to retired.bio.orEmpty().trim()),
        )
        return pairs.filter { (_, values) -> values.first != values.second }.associate { (field, _) -> field to "SURVIVOR" }
    }

    // --- Rollback and concurrency -------------------------------------------------------

    @Test
    fun `a failing audit insert rolls the whole merge back`() = api {
        val f = family()
        val tree = TreeId(f.tree)
        val owner = AccountId(accounts.getValue(Actor.OWNER).id)
        execute(
            """
            create function fail_merge_audit() returns trigger language plpgsql as
            ${'$'}${'$'} begin raise exception '$TRIGGER_MESSAGE'; end ${'$'}${'$'};
            create trigger fail_merge_audit before insert on audit_log
              for each row when (new.entity_type = 'PersonMerge' and new.tree_id = '${f.tree}') execute function fail_merge_audit();
            """.trimIndent(),
        )
        try {
            val before = fingerprint(f.tree)
            val error = assertFails { runBlocking { PersonMergeService.merge(tree, owner, f.request()) } }
            val cause = generateSequence(error) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()
            assertNotNull(cause, "expected a PSQLException in the cause chain, got $error")
            assertEquals("P0001", cause.sqlState)
            assertEquals(before, fingerprint(f.tree), "no reference moved, no person changed or deleted, no audit entry")
        } finally {
            execute("drop trigger fail_merge_audit on audit_log; drop function fail_merge_audit();")
        }
        runBlocking { PersonMergeService.merge(tree, owner, f.request()) }
        assertEquals(0, count("select count(*) from person where id = ?", uuid(f.anaDup.id)))
    }

    @Test
    fun `an event written for the duplicate just before the merge moves to the survivor`() = api {
        val f = family()
        val (create, merge) = coroutineScope {
            val blocker = dataSource.connection
            try {
                blocker.autoCommit = false
                blocker.createStatement().use { it.execute("lock table audit_log in share mode") }
                // The event writer holds the shared graph lock and its row, parked on its audit insert.
                val create = async {
                    client.post("${path(f.tree)}/events", Actor.OWNER, encode(CreateEventRequest(personId = f.anaDup.id, type = "RESIDENCE")))
                }
                awaitBlocked("insert into%audit_log")
                val merge = async { client.post(mergePath(f.tree), Actor.OWNER, encode(f.request())) }
                awaitBlocked("pg_advisory_xact_lock(")
                blocker.commit()
                withTimeout(10_000) { create.await() to merge.await() }
            } finally {
                runCatching { blocker.rollback() }
                blocker.close()
            }
        }
        val event = create.decoded<EventDto>(HttpStatusCode.Created)
        merge.decoded<MergePeopleResponse>()
        assertEquals(f.ana.id, dataSource.query("select person_id from event where id = ?", uuid(event.id)), "no data lost")
    }

    @Test
    fun `an event written for the duplicate during the merge is the usual 404 once it is gone`() = api {
        val f = family()
        val eventsBefore = count("select count(*) from event where tree_id = ?", uuid(f.tree))
        val (merge, create) = coroutineScope {
            val blocker = dataSource.connection
            try {
                blocker.autoCommit = false
                blocker.createStatement().use { it.execute("lock table audit_log in share mode") }
                val merge = async { client.post(mergePath(f.tree), Actor.OWNER, encode(f.request())) }
                awaitBlocked("insert into%audit_log")
                val create = async {
                    client.post("${path(f.tree)}/events", Actor.OWNER, encode(CreateEventRequest(personId = f.anaDup.id, type = "RESIDENCE")))
                }
                awaitBlocked("pg_advisory_xact_lock_shared")
                blocker.commit()
                withTimeout(10_000) { merge.await() to create.await() }
            } finally {
                runCatching { blocker.rollback() }
                blocker.close()
            }
        }
        merge.decoded<MergePeopleResponse>()
        assertError(create, HttpStatusCode.NotFound, "No person with id ${f.anaDup.id}", "late event")
        assertEquals(eventsBefore, count("select count(*) from event where tree_id = ?", uuid(f.tree)))
    }

    @Test
    fun `a citation made before the merge moves and a citation made after it gets 404`() = api {
        // Citation first: the writer holds the duplicate FOR KEY SHARE, parked on its audit insert.
        val first = family()
        val (cite, merge) = coroutineScope {
            val blocker = dataSource.connection
            try {
                blocker.autoCommit = false
                blocker.createStatement().use { it.execute("lock table audit_log in share mode") }
                val cite = async {
                    client.post("${path(first.tree)}/citations", Actor.OWNER, encode(CreateCitationRequest(first.source.id, "PERSON", first.anaDup.id, confidence = 1)))
                }
                awaitBlocked("insert into%audit_log")
                val merge = async { client.post(mergePath(first.tree), Actor.OWNER, encode(first.request())) }
                awaitBlocked("for update")
                blocker.commit()
                withTimeout(10_000) { cite.await() to merge.await() }
            } finally {
                runCatching { blocker.rollback() }
                blocker.close()
            }
        }
        val citation = cite.decoded<CitationDto>(HttpStatusCode.Created)
        merge.decoded<MergePeopleResponse>()
        assertEquals(first.ana.id, dataSource.query("select target_id from citation where id = ?", uuid(citation.id)))

        // Merge first: the citation writer's lock waits, then finds no person.
        val second = family()
        val citationsBefore = count("select count(*) from citation where tree_id = ?", uuid(second.tree))
        val (merged, late) = coroutineScope {
            val blocker = dataSource.connection
            try {
                blocker.autoCommit = false
                blocker.createStatement().use { it.execute("lock table audit_log in share mode") }
                val merged = async { client.post(mergePath(second.tree), Actor.OWNER, encode(second.request())) }
                awaitBlocked("insert into%audit_log")
                val late = async {
                    client.post("${path(second.tree)}/citations", Actor.OWNER, encode(CreateCitationRequest(second.source.id, "PERSON", second.anaDup.id, confidence = 1)))
                }
                awaitBlocked("for key share")
                blocker.commit()
                withTimeout(10_000) { merged.await() to late.await() }
            } finally {
                runCatching { blocker.rollback() }
                blocker.close()
            }
        }
        merged.decoded<MergePeopleResponse>()
        assertError(late, HttpStatusCode.NotFound, "No person with id ${second.anaDup.id}", "late citation")
        assertEquals(citationsBefore, count("select count(*) from citation where tree_id = ?", uuid(second.tree)))
    }

    @Test
    fun `two merges of the same duplicate leave exactly one winner`() = api {
        val f = family()
        val other = client.person(f.tree, Actor.OWNER, "Ana María", living = true, paternal = "Romero", maternal = "Vidal", sex = "FEMALE", bio = "Teacher")
        val responses = coroutineScope {
            listOf(
                async { client.post(mergePath(f.tree), Actor.OWNER, encode(f.request())) },
                async { client.post(mergePath(f.tree), Actor.OWNER, encode(MergePeopleRequest(other.id, other.version, f.anaDup.id, f.anaDup.version, emptyMap()))) },
            ).map { it.await() }
        }
        assertEquals(1, responses.count { it.status == HttpStatusCode.OK }, responses.joinToString { it.status.toString() })
        assertTrue(responses.any { it.status == HttpStatusCode.NotFound || it.status == HttpStatusCode.Conflict })
        assertEquals(1, count("select count(*) from audit_log where tree_id = ? and entity_type = 'PersonMerge' and entity_id like ?", uuid(f.tree), "%:${f.anaDup.id}"))
    }
}
