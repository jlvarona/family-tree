package com.moxietech.family_tree.server

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
import com.moxietech.family_tree.api.TreeDto
import com.moxietech.family_tree.api.TreeGraphDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.module
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
import com.moxietech.family_tree.server.identity.IdentityTestApi.treeIds
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * FT-072: the FT-065, FT-066 and FT-068 acceptance cases over HTTP, through the
 * production [module] and verifier. Every request carries an RS256 access token
 * signed by a run-only key that [TestJwks] serves over loopback HTTP, with a
 * fixed issuer and a stable subject per actor, so each actor resolves to the
 * same account on every request (FT-003). Data lives in a disposable
 * PostgreSQL 16 container migrated by Flyway, and is seeded through the API.
 *
 * Actors:
 * - owner A owns tree A ("Familia López"); owner B owns tree B.
 * - relative is an ACTIVE non-owner member of tree A.
 * - invitee holds only an INVITED membership in tree A.
 *
 * Tree A (living people marked *):
 *   Antonio + Rosa (married 1950)            ->  Carmen*
 *   Carmen* + Luis (married 1980, ended 2010) ->  Sofia*
 * Tree B: Pedro + Lucia, a place (Lima), a source and an event with notes.
 *
 * There is no invitation route yet, so the two non-owner memberships are
 * inserted directly, as in the service-level suites.
 */
class AuthenticatedAccessTest {

    private enum class Actor(val subject: String, val email: String) {
        OWNER_A("ft072-owner-a", "owner-a@example.com"),
        OWNER_B("ft072-owner-b", "owner-b@example.com"),
        RELATIVE("ft072-relative", "relative@example.com"),
        INVITEE("ft072-invitee", "invitee@example.com"),

        /** Active non-owner of a tree the FT-004 snapshot test creates; holds nothing else. */
        SNAPSHOT_READER("ft004-snapshot-reader", "snapshot-reader@example.com"),
    }

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val ISSUER = "http://keycloak.test/realms/family-tree"
        private val json = Json { ignoreUnknownKeys = true }
        private val tables = listOf("person", "union_record", "union_child", "event", "place", "source", "membership", "audit_log")

        private lateinit var dataSource: HikariDataSource
        private lateinit var jwks: TestJwks
        private lateinit var accounts: Map<Actor, AccountDto>

        private lateinit var treeA: TreeDto
        private lateinit var antonio: String
        private lateinit var rosa: String
        private lateinit var carmen: String
        private lateinit var luis: String
        private lateinit var sofia: String
        private lateinit var grandparentsUnion: String
        private lateinit var parentsUnion: String

        private lateinit var treeB: TreeDto
        private lateinit var pedro: String
        private lateinit var lucia: String
        private lateinit var unionB: String
        private lateinit var placeB: String
        private lateinit var sourceB: String

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
            seed()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::jwks.isInitialized) jwks.close()
            if (::dataSource.isInitialized) dataSource.close()
        }

        private fun settings() = OidcSettings(issuer = ISSUER, jwksUri = jwks.jwksUri, audience = DEFAULT_API_AUDIENCE)

        /** A fresh Keycloak-shaped access token for [actor]: same issuer and subject every time. */
        private fun token(actor: Actor): String = jwks.accessToken(ISSUER, email = actor.email) { withSubject(actor.subject) }

        /** Seeds both trees through the production routes, as their owners. */
        private fun seed() = testApplication {
            application { module(settings()) }
            accounts = Actor.entries.associateWith { client.me(token(it)) }
            treeA = client.createTree(token(Actor.OWNER_A), "Familia López")
            treeB = client.createTree(token(Actor.OWNER_B), "Family B")

            MembershipRepository.insert(
                Uuid.parse(treeA.id), Uuid.parse(accounts.getValue(Actor.RELATIVE).id), personId = null, status = MembershipStatus.ACTIVE,
            )
            MembershipRepository.insert(
                Uuid.parse(treeA.id), Uuid.parse(accounts.getValue(Actor.INVITEE).id), personId = null, status = MembershipStatus.INVITED,
            )

            val a = path(treeA.id)
            fun person(given: String, surname: String, living: Boolean, sex: String = "UNKNOWN", bio: String? = null) =
                CreatePersonRequest(givenNames = given, surnamePaternal = surname, sex = sex, isLiving = living, bio = bio)
            suspend fun createPerson(tree: String, owner: Actor, request: CreatePersonRequest): String =
                client.write("$tree/people", owner, encode(request)).decoded<CreatePersonResponse>(HttpStatusCode.Created).person.id
            suspend fun createUnion(tree: String, owner: Actor, request: CreateUnionRequest): String =
                client.write("$tree/unions", owner, encode(request)).decoded<UnionDto>(HttpStatusCode.Created).id
            suspend fun addChild(tree: String, owner: Actor, union: String, child: String) {
                client.write("$tree/unions/$union/children", owner, encode(AddUnionChildRequest(child, "BIRTH")))
                    .decoded<UnionDto>(HttpStatusCode.Created)
            }

            antonio = createPerson(a, Actor.OWNER_A, person("Antonio", "López", living = false, sex = "MALE"))
            rosa = createPerson(a, Actor.OWNER_A, person("Rosa", "Martín", living = false))
            carmen = createPerson(a, Actor.OWNER_A, person("Carmen", "López", living = true, sex = "FEMALE", bio = "Lives in Madrid"))
            luis = createPerson(a, Actor.OWNER_A, person("Luis", "Ortega", living = false, sex = "MALE"))
            sofia = createPerson(a, Actor.OWNER_A, person("Sofia", "Ortega", living = true))
            grandparentsUnion = createUnion(a, Actor.OWNER_A, CreateUnionRequest(antonio, rosa, type = "MARRIED", startDate = "1950"))
            addChild(a, Actor.OWNER_A, grandparentsUnion, carmen)
            parentsUnion = createUnion(
                a, Actor.OWNER_A, CreateUnionRequest(carmen, luis, type = "MARRIED", startDate = "1980", endDate = "2010"),
            )
            addChild(a, Actor.OWNER_A, parentsUnion, sofia)
            val madrid = client.write("$a/places", Actor.OWNER_A, encode(CreatePlaceRequest("Madrid")))
                .decoded<PlaceDto>(HttpStatusCode.Created).id
            client.write(
                "$a/events", Actor.OWNER_A,
                encode(CreateEventRequest(personId = carmen, type = "BIRTH", date = "12 MAR 1955", placeId = madrid, notes = "Hospital")),
            ).decoded<EventDto>(HttpStatusCode.Created)
            client.write("$a/events", Actor.OWNER_A, encode(CreateEventRequest(personId = luis, type = "DEATH", date = "2010")))
                .decoded<EventDto>(HttpStatusCode.Created)
            client.write("$a/sources", Actor.OWNER_A, encode(CreateSourceRequest(title = "Parish register", type = "CHURCH_RECORD")))
                .decoded<SourceDto>(HttpStatusCode.Created)

            val b = path(treeB.id)
            pedro = createPerson(b, Actor.OWNER_B, person("Pedro", "Quispe", living = false))
            lucia = createPerson(b, Actor.OWNER_B, person("Lucia", "Mamani", living = false))
            unionB = createUnion(b, Actor.OWNER_B, CreateUnionRequest(pedro, lucia, type = "MARRIED", startDate = "1901"))
            placeB = client.write("$b/places", Actor.OWNER_B, encode(CreatePlaceRequest("Lima")))
                .decoded<PlaceDto>(HttpStatusCode.Created).id
            sourceB = client.write("$b/sources", Actor.OWNER_B, encode(CreateSourceRequest(title = "Lima census", type = "CENSUS")))
                .decoded<SourceDto>(HttpStatusCode.Created).id
            client.write(
                "$b/events", Actor.OWNER_B,
                encode(CreateEventRequest(personId = pedro, type = "BIRTH", date = "1901", placeId = placeB, notes = "tree-b-secret")),
            ).decoded<EventDto>(HttpStatusCode.Created)
        }

        // --- HTTP helpers --------------------------------------------------------

        private fun path(treeId: String) = "/api/v1/trees/$treeId"

        private fun randomId() = UUID.randomUUID().toString()

        private inline fun <reified T> encode(value: T): String = json.encodeToString(value)

        private suspend inline fun <reified T> HttpResponse.decoded(status: HttpStatusCode = HttpStatusCode.OK): T =
            json.decodeFromString(bodyWithStatus(status))

        private suspend fun HttpClient.read(path: String, actor: Actor): HttpResponse =
            request(path) {
                method = HttpMethod.Get
                bearerAuth(token(actor))
            }

        private suspend fun HttpClient.write(path: String, actor: Actor, body: String): HttpResponse =
            request(path) {
                method = HttpMethod.Post
                bearerAuth(token(actor))
                contentType(ContentType.Application.Json)
                setBody(body)
            }
    }

    private fun api(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module(settings()) }
        block()
    }

    /** Every read route under tree A. */
    private fun readPaths(): List<String> {
        val a = path(treeA.id)
        return listOf(
            a, "$a/people", "$a/people/$carmen", "$a/people/$sofia/ancestors", "$a/people/$antonio/descendants",
            "$a/people/$carmen/events", "$a/unions", "$a/places", "$a/sources", "$a/graph",
        )
    }

    /**
     * Every current mutation under [treeId], with bodies that reference rows in
     * that tree, so each request would succeed for that tree's owner and a
     * rejection can only come from the access check.
     */
    private fun writes(treeId: String, partner1: String, partner2: String, union: String): List<Pair<String, String>> {
        val t = path(treeId)
        return listOf(
            "$t/people" to encode(CreatePersonRequest(givenNames = "Pablo", sex = "UNKNOWN", isLiving = false)),
            "$t/unions" to encode(CreateUnionRequest(partner1, partner2, type = "PARTNERS")),
            "$t/unions/$union/children" to encode(AddUnionChildRequest(partner1, "ADOPTED")),
            "$t/events" to encode(CreateEventRequest(personId = partner1, type = "BURIAL")),
            "$t/places" to encode(CreatePlaceRequest("Cusco")),
            "$t/sources" to encode(CreateSourceRequest(title = "Census", type = "CENSUS")),
        )
    }

    private fun writesToTreeA() = writes(treeA.id, antonio, luis, parentsUnion)

    private suspend fun assertError(response: HttpResponse, status: HttpStatusCode, message: String, context: String) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, "$context: $body")
        assertEquals(message, json.decodeFromString<ApiErrorResponse>(body).message, context)
    }

    /** Nothing that identifies tree B's rows, other than an ID the caller sent, appears in [body]. */
    private fun assertNoTreeBData(body: String, context: String, echoed: String? = null) {
        listOf(treeB.id, pedro, lucia, unionB, placeB, sourceB).filter { it != echoed }
            .forEach { assertFalse(it in body, "$context leaked tree B's ID $it: $body") }
        assertAbsent(body, listOf("Family B", "Pedro", "Quispe", "Lucia", "Mamani", "Lima", "tree-b-secret", "1901"), context)
    }

    /**
     * None of [values] appears in [body] outside a UUID. UUIDs are masked first,
     * so a year such as "1980" can't match by chance inside a random ID.
     */
    private fun assertAbsent(body: String, values: List<String>, context: String) {
        val masked = body.replace(uuidPattern, "<uuid>")
        values.forEach { assertFalse(it in masked, "$context leaked '$it': $body") }
    }

    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** Per-tree row counts of every graph table, memberships and the audit log. */
    private fun rowCounts(): Map<String, Long> = buildMap {
        dataSource.connection.use { connection ->
            for (table in tables) for ((label, id) in listOf("A" to treeA.id, "B" to treeB.id)) {
                connection.prepareStatement("select count(*) from $table where tree_id = ?").use { st ->
                    st.setObject(1, UUID.fromString(id))
                    st.executeQuery().use { rs -> rs.next(); put("$table/$label", rs.getLong(1)) }
                }
            }
        }
    }

    private fun assertPlaceholder(dto: PersonDto) {
        assertEquals("Private", dto.givenNames, dto.id)
        assertEquals("Private", dto.fullName, dto.id)
        assertNull(dto.surnamePaternal, dto.id)
        assertNull(dto.surnameMaternal, dto.id)
        assertNull(dto.bio, dto.id)
        assertNull(dto.profileMediaId, dto.id)
        assertEquals("UNKNOWN", dto.sex, dto.id)
        assertTrue(dto.isLiving, dto.id)
    }

    // --- FT-065 tenant isolation ----------------------------------------------------

    @Test
    fun `a person from another tree reads exactly like a missing person`() = api {
        val missing = randomId()
        val a = path(treeA.id)
        for (suffix in listOf("", "/ancestors", "/descendants", "/events")) {
            val foreign = client.read("$a/people/$pedro$suffix", Actor.OWNER_A)
            val absent = client.read("$a/people/$missing$suffix", Actor.OWNER_A)
            val foreignBody = foreign.bodyAsText()
            assertEquals(HttpStatusCode.NotFound, foreign.status, "foreign $suffix: $foreignBody")
            assertEquals(HttpStatusCode.NotFound, absent.status, "missing $suffix")
            assertEquals(absent.bodyAsText().replace(missing, "<id>"), foreignBody.replace(pedro, "<id>"), suffix)
            assertNoTreeBData(foreignBody, "person$suffix", echoed = pedro)
        }
    }

    @Test
    fun `tree-wide lists contain only the caller's tree`() = api {
        val a = path(treeA.id)
        val people = client.read("$a/people", Actor.OWNER_A).decoded<List<PersonDto>>()
        assertTrue(people.all { it.treeId == treeA.id })
        assertTrue(people.map { it.id }.containsAll(listOf(antonio, rosa, carmen, luis, sofia)))
        val unions = client.read("$a/unions", Actor.OWNER_A).decoded<List<UnionDto>>()
        assertEquals(setOf(grandparentsUnion, parentsUnion), unions.map { it.id }.toSet())
        assertTrue(client.read("$a/places", Actor.OWNER_A).decoded<List<PlaceDto>>().none { it.id == placeB })
        assertTrue(client.read("$a/sources", Actor.OWNER_A).decoded<List<SourceDto>>().all { it.treeId == treeA.id })
        for (suffix in listOf("/people", "/unions", "/places", "/sources", "/people/$luis/events")) {
            assertNoTreeBData(client.read("$a$suffix", Actor.OWNER_A).bodyAsText(), suffix)
        }
    }

    @Test
    fun `another family's tree is closed to an owner of a different tree, and a missing tree is not found`() = api {
        assertEquals(listOf(treeA.id), client.treeIds(token(Actor.OWNER_A)))
        val b = path(treeB.id)
        val notMember = "Not a member of tree ${treeB.id}"
        for (path in listOf(b, "$b/people", "$b/people/$pedro", "$b/people/$pedro/events", "$b/unions", "$b/places", "$b/sources")) {
            val response = client.read(path, Actor.OWNER_A)
            assertError(response, HttpStatusCode.Forbidden, notMember, path)
            assertNoTreeBData(response.bodyAsText().replace(notMember, ""), path)
        }
        val missing = randomId()
        assertError(client.read(path(missing), Actor.OWNER_A), HttpStatusCode.NotFound, "No tree with id $missing", "missing tree")
        assertError(
            client.read("${path(missing)}/people", Actor.OWNER_A), HttpStatusCode.NotFound, "No tree with id $missing", "missing tree people",
        )
    }

    @Test
    fun `writes into another family's tree are rejected and change nothing`() = api {
        val before = rowCounts()
        for ((path, body) in writes(treeB.id, pedro, lucia, unionB)) {
            assertError(client.write(path, Actor.OWNER_A, body), HttpStatusCode.Forbidden, "Not a member of tree ${treeB.id}", path)
        }
        assertEquals(before, rowCounts())
    }

    @Test
    fun `writes referencing another tree's rows fail like missing IDs and change nothing`() = api {
        val a = path(treeA.id)
        // Each case swaps exactly one reference for [id]: tree B's row, then an ID that exists nowhere.
        data class Case(val name: String, val foreignId: String, val request: (id: String) -> Pair<String, String>)
        val cases = listOf(
            Case("first union partner", pedro) { id -> "$a/unions" to encode(CreateUnionRequest(id, type = "UNKNOWN")) },
            Case("second union partner", pedro) { id -> "$a/unions" to encode(CreateUnionRequest(antonio, id, type = "MARRIED")) },
            Case("union of a child link", unionB) { id ->
                "$a/unions/$id/children" to encode(AddUnionChildRequest(luis, "ADOPTED"))
            },
            Case("child of a child link", pedro) { id ->
                "$a/unions/$grandparentsUnion/children" to encode(AddUnionChildRequest(id, "BIRTH"))
            },
            Case("event person", pedro) { id -> "$a/events" to encode(CreateEventRequest(personId = id, type = "DEATH")) },
            Case("event union", unionB) { id -> "$a/events" to encode(CreateEventRequest(unionId = id, type = "MARRIAGE")) },
            Case("event place", placeB) { id ->
                "$a/events" to encode(CreateEventRequest(personId = luis, type = "BURIAL", placeId = id))
            },
        )
        val before = rowCounts()
        for (case in cases) {
            val missing = randomId()
            val (foreignPath, foreignBody) = case.request(case.foreignId)
            val (missingPath, missingBody) = case.request(missing)
            val foreign = client.write(foreignPath, Actor.OWNER_A, foreignBody)
            val absent = client.write(missingPath, Actor.OWNER_A, missingBody)
            val foreignText = foreign.bodyAsText()
            assertEquals(HttpStatusCode.NotFound, foreign.status, "${case.name} (foreign): $foreignText")
            assertEquals(HttpStatusCode.NotFound, absent.status, "${case.name} (missing)")
            assertEquals(absent.bodyAsText().replace(missing, "<id>"), foreignText.replace(case.foreignId, "<id>"), case.name)
            assertNoTreeBData(foreignText, case.name, echoed = case.foreignId)
        }
        assertEquals(before, rowCounts())
    }

    // --- FT-066 living-person privacy -------------------------------------------------

    @Test
    fun `the owner sees living relatives, their events and union dates in full`() = api {
        val a = path(treeA.id)
        val people = client.read("$a/people", Actor.OWNER_A).decoded<List<PersonDto>>().associateBy { it.id }
        assertEquals("Carmen López", people.getValue(carmen).fullName)
        assertEquals("Lives in Madrid", people.getValue(carmen).bio)
        assertEquals("FEMALE", people.getValue(carmen).sex)
        assertEquals("Sofia Ortega", client.read("$a/people/$sofia", Actor.OWNER_A).decoded<PersonDto>().fullName)

        val event = client.read("$a/people/$carmen/events", Actor.OWNER_A).decoded<List<EventDto>>().single()
        assertEquals("12 MAR 1955", event.date?.displayText)
        assertEquals("Madrid", event.place?.name)
        assertEquals("Hospital", event.notes)

        val parents = client.read("$a/unions", Actor.OWNER_A).decoded<List<UnionDto>>().single { it.id == parentsUnion }
        assertEquals("1980", parents.startDate?.displayText)
        assertEquals("2010", parents.endDate?.displayText)
    }

    @Test
    fun `a member sees living relatives as placeholders in person and branch views`() = api {
        val a = path(treeA.id)
        val listResponse = client.read("$a/people", Actor.RELATIVE)
        val people = listResponse.decoded<List<PersonDto>>().associateBy { it.id }
        assertPlaceholder(people.getValue(carmen))
        assertPlaceholder(people.getValue(sofia))
        assertEquals("Antonio López", people.getValue(antonio).fullName)
        assertEquals("Luis Ortega", people.getValue(luis).fullName)
        assertAbsent(listResponse.bodyAsText(), listOf("Carmen", "Sofia", "Lives in Madrid"), "people list")

        assertPlaceholder(client.read("$a/people/$carmen", Actor.RELATIVE).decoded<PersonDto>())
        assertEquals("Rosa Martín", client.read("$a/people/$rosa", Actor.RELATIVE).decoded<PersonDto>().fullName)

        val ancestors = client.read("$a/people/$sofia/ancestors", Actor.RELATIVE).decoded<List<PersonDto>>().associateBy { it.id }
        assertEquals(setOf(carmen, luis, antonio, rosa), ancestors.keys)
        assertPlaceholder(ancestors.getValue(carmen))
        assertEquals("Luis Ortega", ancestors.getValue(luis).fullName)

        val descendants = client.read("$a/people/$antonio/descendants", Actor.RELATIVE).decoded<List<PersonDto>>()
        assertEquals(setOf(carmen, sofia), descendants.map { it.id }.toSet())
        descendants.forEach(::assertPlaceholder)
    }

    @Test
    fun `a member gets no events, places or notes for a living relative`() = api {
        val a = path(treeA.id)
        val response = client.read("$a/people/$carmen/events", Actor.RELATIVE)
        assertEquals(emptyList(), response.decoded<List<EventDto>>())
        assertAbsent(response.bodyAsText(), listOf("1955", "Madrid", "Hospital"), "living relative's events")

        val luisEvents = client.read("$a/people/$luis/events", Actor.RELATIVE).decoded<List<EventDto>>()
        assertEquals("2010", luisEvents.single().date?.displayText)
    }

    @Test
    fun `a member sees a living partner's union type and links but not its dates`() = api {
        val response = client.read("${path(treeA.id)}/unions", Actor.RELATIVE)
        val unions = response.decoded<List<UnionDto>>().associateBy { it.id }

        val parents = unions.getValue(parentsUnion)
        assertEquals("MARRIED", parents.type)
        assertEquals(carmen, parents.partner1Id)
        assertEquals(luis, parents.partner2Id)
        assertEquals(listOf(sofia), parents.children.map { it.childId })
        assertNull(parents.startDate)
        assertNull(parents.endDate)
        assertAbsent(response.bodyAsText(), listOf("1980", "2010"), "unions")

        // A living child doesn't hide the deceased parents' union date.
        val grandparents = unions.getValue(grandparentsUnion)
        assertEquals("MARRIED", grandparents.type)
        assertEquals("1950", grandparents.startDate?.displayText)
        assertEquals(listOf(carmen), grandparents.children.map { it.childId })
    }

    @Test
    fun `duplicate warnings reach only the owner, who sees the real match`() = api {
        val a = path(treeA.id)
        val request = encode(CreatePersonRequest("Carmen", "Lopez", sex = "FEMALE", isLiving = true))
        val before = rowCounts()
        // A member without an editing grant can't create people, so the duplicate check never runs for them (FT-068, FT-012).
        val asRelative = client.write("$a/people", Actor.RELATIVE, request)
        assertError(asRelative, HttpStatusCode.Forbidden, "Your access to tree ${treeA.id} doesn't allow changes", "relative create")
        assertAbsent(asRelative.bodyAsText(), listOf("Carmen", "López"), "relative create")
        assertEquals(before, rowCounts())

        val created = client.write("$a/people", Actor.OWNER_A, request).decoded<CreatePersonResponse>(HttpStatusCode.Created)
        assertTrue(created.possibleDuplicates.any { it.person.id == carmen && it.person.fullName == "Carmen López" })
    }

    // --- FT-068 membership and owner-only writes ----------------------------------------

    @Test
    fun `an invited account cannot list, read or change the tree`() = api {
        assertTrue(client.treeIds(token(Actor.INVITEE)).isEmpty())
        val notMember = "Not a member of tree ${treeA.id}"
        val before = rowCounts()
        for (path in readPaths()) {
            assertError(client.read(path, Actor.INVITEE), HttpStatusCode.Forbidden, notMember, "read $path")
        }
        for ((path, body) in writesToTreeA()) {
            assertError(client.write(path, Actor.INVITEE, body), HttpStatusCode.Forbidden, notMember, "write $path")
        }
        assertEquals(before, rowCounts())
    }

    @Test
    fun `an active non-owner can use every read route`() = api {
        assertEquals(accounts.getValue(Actor.RELATIVE).id, client.me(token(Actor.RELATIVE)).id)
        assertEquals(listOf(treeA.id), client.treeIds(token(Actor.RELATIVE)))
        for (path in readPaths()) {
            val response = client.read(path, Actor.RELATIVE)
            assertEquals(HttpStatusCode.OK, response.status, "$path: ${response.bodyAsText()}")
        }
        val people = client.read("${path(treeA.id)}/people", Actor.RELATIVE).decoded<List<PersonDto>>()
        assertTrue(people.map { it.id }.containsAll(listOf(antonio, rosa, carmen, luis, sofia)))
    }

    @Test
    fun `an active member without an editing grant gets 403 for every write, valid or malformed, and nothing changes`() = api {
        // The relative holds no grant (FT-012): no write is open to them.
        val noEditAccess = "Your access to tree ${treeA.id} doesn't allow changes"
        val before = rowCounts()
        for ((path, body) in writesToTreeA()) {
            assertError(client.write(path, Actor.RELATIVE, body), HttpStatusCode.Forbidden, noEditAccess, "valid $path")
            assertError(client.write(path, Actor.RELATIVE, "{ not json"), HttpStatusCode.Forbidden, noEditAccess, "malformed $path")
        }
        assertEquals(before, rowCounts())
    }

    @Test
    fun `the owner's writes succeed and are audited in the owner's tree only`() = api {
        val before = rowCounts()
        val (path, body) = writesToTreeA().single { it.first.endsWith("/sources") }
        val source = client.write(path, Actor.OWNER_A, body).decoded<SourceDto>(HttpStatusCode.Created)
        assertEquals(treeA.id, source.treeId)
        val after = rowCounts()
        assertEquals(before.getValue("source/A") + 1, after.getValue("source/A"))
        assertEquals(before.getValue("audit_log/A") + 1, after.getValue("audit_log/A"))
        assertEquals(before.filterKeys { it.endsWith("/B") }, after.filterKeys { it.endsWith("/B") })
    }

    // --- FT-004 whole-tree bundle -------------------------------------------------------

    @Test
    fun `the owner's whole-tree bundle has every person, union and child link in full`() = api {
        val a = path(treeA.id)
        val graph = client.read("$a/graph", Actor.OWNER_A).decoded<TreeGraphDto>()
        assertEquals(treeA.id, graph.treeId)
        // Same content as the separate list routes, which keep working.
        val people = client.read("$a/people", Actor.OWNER_A).decoded<List<PersonDto>>()
        val unions = client.read("$a/unions", Actor.OWNER_A).decoded<List<UnionDto>>()
        assertEquals(people.associateBy { it.id }, graph.people.associateBy { it.id })
        assertEquals(unions.associateBy { it.id }, graph.unions.associateBy { it.id })

        val byId = graph.people.associateBy { it.id }
        assertTrue(byId.keys.containsAll(listOf(antonio, rosa, carmen, luis, sofia)))
        assertEquals("Carmen López", byId.getValue(carmen).fullName)
        assertEquals("Lives in Madrid", byId.getValue(carmen).bio)
        val graphUnions = graph.unions.associateBy { it.id }
        assertEquals(setOf(grandparentsUnion, parentsUnion), graphUnions.keys)
        val parents = graphUnions.getValue(parentsUnion)
        assertEquals("MARRIED", parents.type)
        assertEquals("1980", parents.startDate?.displayText)
        assertEquals("2010", parents.endDate?.displayText)
        assertEquals(listOf(sofia to "BIRTH"), parents.children.map { it.childId to it.relation })
        assertEquals(listOf(carmen), graphUnions.getValue(grandparentsUnion).children.map { it.childId })
    }

    @Test
    fun `a member's whole-tree bundle has placeholders and keeps union type and links without hidden dates`() = api {
        val response = client.read("${path(treeA.id)}/graph", Actor.RELATIVE)
        val graph = response.decoded<TreeGraphDto>()
        val people = graph.people.associateBy { it.id }
        assertPlaceholder(people.getValue(carmen))
        assertPlaceholder(people.getValue(sofia))
        assertEquals("Antonio López", people.getValue(antonio).fullName)
        assertEquals("Luis Ortega", people.getValue(luis).fullName)

        val unions = graph.unions.associateBy { it.id }
        val parents = unions.getValue(parentsUnion)
        assertEquals("MARRIED", parents.type)
        assertEquals(carmen, parents.partner1Id)
        assertEquals(luis, parents.partner2Id)
        assertEquals(listOf(sofia), parents.children.map { it.childId })
        assertNull(parents.startDate)
        assertNull(parents.endDate)
        val grandparents = unions.getValue(grandparentsUnion)
        assertEquals("1950", grandparents.startDate?.displayText)
        assertEquals(listOf(carmen), grandparents.children.map { it.childId })
        assertAbsent(response.bodyAsText(), listOf("Carmen", "Sofia", "Lives in Madrid", "1980", "2010"), "member graph")
    }

    @Test
    fun `the whole-tree bundle stays in its tree and is closed to non-members`() = api {
        val body = client.read("${path(treeA.id)}/graph", Actor.OWNER_A).bodyAsText()
        assertNoTreeBData(body, "tree A graph")
        val graph = json.decodeFromString<TreeGraphDto>(body)
        assertTrue(graph.people.all { it.treeId == treeA.id })
        assertTrue(graph.unions.all { it.treeId == treeA.id })

        val bGraph = "${path(treeB.id)}/graph"
        val notMemberB = "Not a member of tree ${treeB.id}"
        val foreign = client.read(bGraph, Actor.OWNER_A)
        assertError(foreign, HttpStatusCode.Forbidden, notMemberB, "other owner")
        assertNoTreeBData(foreign.bodyAsText().replace(notMemberB, ""), "other owner")
        assertError(client.read(bGraph, Actor.RELATIVE), HttpStatusCode.Forbidden, notMemberB, "member of another tree")
        assertError(
            client.read("${path(treeA.id)}/graph", Actor.INVITEE), HttpStatusCode.Forbidden, "Not a member of tree ${treeA.id}", "invitee",
        )
        val missing = randomId()
        assertError(client.read("${path(missing)}/graph", Actor.OWNER_A), HttpStatusCode.NotFound, "No tree with id $missing", "missing")
    }

    @Test
    fun `an empty tree's bundle has no people and no unions`() = api {
        val tree = client.createTree(token(Actor.OWNER_B), "FT-004 empty").id
        val graph = client.read("${path(tree)}/graph", Actor.OWNER_B).decoded<TreeGraphDto>()
        assertEquals(TreeGraphDto(treeId = tree, people = emptyList(), unions = emptyList()), graph)
    }

    /**
     * FT-004 review: the bundle's queries share one snapshot. A lock on
     * `union_child` parks a non-owner's bundle read after it has read people;
     * a living person and their dated union are then committed. That read must
     * show neither (no dangling link, no dates exposed because the new partner
     * was missing from the visibility set); a fresh read shows them redacted.
     */
    @Test
    fun `a bundle read keeps one snapshot while a living partner and dated union are committed`() = api {
        val tree = client.createTree(token(Actor.OWNER_B), "FT-004 snapshot").id
        MembershipRepository.insert(
            Uuid.parse(tree), Uuid.parse(accounts.getValue(Actor.SNAPSHOT_READER).id), personId = null, status = MembershipStatus.ACTIVE,
        )
        val t = path(tree)
        val elena = client.write(
            "$t/people", Actor.OWNER_B, encode(CreatePersonRequest(givenNames = "Elena", sex = "FEMALE", isLiving = false)),
        ).decoded<CreatePersonResponse>(HttpStatusCode.Created).person.id
        val newcomer = randomId()
        val union = randomId()

        coroutineScope {
            val lock = dataSource.connection
            try {
                lock.autoCommit = false
                lock.createStatement().use { it.execute("lock table union_child in access exclusive mode") }
                val pending = async { client.read("$t/graph", Actor.SNAPSHOT_READER) }
                awaitBlockedRead("union_child")
                lock.prepareStatement(
                    "insert into person (id, tree_id, given_names, sex, is_living) values (?, ?, 'Valeria', 'FEMALE', true)",
                ).use { st ->
                    st.setObject(1, uuid(newcomer)); st.setObject(2, uuid(tree)); st.executeUpdate()
                }
                lock.prepareStatement(
                    """
                    insert into union_record (id, tree_id, partner1_id, partner2_id, type,
                        start_date_text, start_date_earliest, start_date_latest)
                    values (?, ?, ?, ?, 'MARRIED', '1999', date '1999-01-01', date '1999-12-31')
                    """.trimIndent(),
                ).use { st ->
                    st.setObject(1, uuid(union)); st.setObject(2, uuid(tree))
                    st.setObject(3, uuid(elena)); st.setObject(4, uuid(newcomer)); st.executeUpdate()
                }
                lock.commit()

                val snapshot = withTimeout(10_000) { pending.await() }.decoded<TreeGraphDto>()
                assertEquals(listOf(elena), snapshot.people.map { it.id })
                assertEquals(emptyList(), snapshot.unions)
            } finally {
                // Releases the lock (if not committed) before the scope waits for the read.
                runCatching { lock.rollback() }
                lock.close()
            }
        }

        val response = client.read("$t/graph", Actor.SNAPSHOT_READER)
        val fresh = response.decoded<TreeGraphDto>()
        assertPlaceholder(fresh.people.single { it.id == newcomer })
        val added = fresh.unions.single()
        assertEquals(union, added.id)
        assertEquals("MARRIED", added.type)
        assertEquals(elena, added.partner1Id)
        assertEquals(newcomer, added.partner2Id)
        assertNull(added.startDate)
        assertNull(added.endDate)
        assertAbsent(response.bodyAsText(), listOf("Valeria", "1999"), "fresh graph")
    }

    /** Waits, at most 10 s, until another session is waiting on a lock while querying [table]. */
    private suspend fun awaitBlockedRead(table: String) = withTimeout(10_000) {
        val sql = """
            select count(*) from pg_stat_activity
            where pid <> pg_backend_pid() and wait_event_type = 'Lock' and query ilike ?
        """.trimIndent()
        while (dataSource.query(sql, "%$table%")!!.toLong() == 0L) delay(25)
    }

    // --- FT-071 date bounds round-trip through PostgreSQL ----------------------------------

    @Test
    fun `storable date limits are stored and returned exactly, and an unsupported year stays text-only`() = api {
        val f = newFamily()
        val t = path(f.tree)
        data class Case(val text: String, val stored: String?, val returned: String?)
        val cases = listOf(
            // ISO -4713-11-24 is PostgreSQL's first date, 24 Nov 4714 BC. A legacy Calendar
            // conversion stored 4713-01-01 BC (returned -4713-11-23); pgjdbc's LocalDate
            // binding stores -infinity. Both must be bypassed.
            Case("24 Nov -4713", "4714-11-24 BC", "-4713-11-24"),
            Case("31 Dec 5874897", "5874897-12-31", "+5874897-12-31"),
            Case("5874898", null, null),
        )
        for (case in cases) {
            val event = client.write("$t/events", Actor.OWNER_B, encode(CreateEventRequest(personId = f.father, type = "BIRTH", date = case.text)))
                .decoded<EventDto>(HttpStatusCode.Created)
            for (column in listOf("date_earliest", "date_latest")) {
                val stored = dataSource.query("select $column::text from event where id = ? and tree_id = ?", uuid(event.id), uuid(f.tree))
                assertEquals(case.stored, stored, "${case.text} $column")
            }
            val read = client.read("$t/people/${f.father}/events", Actor.OWNER_B).decoded<List<EventDto>>().single { it.id == event.id }
            for (date in listOf(event.date, read.date)) {
                assertEquals(case.text, date?.displayText, case.text)
                assertEquals(case.returned, date?.earliestIso, case.text)
                assertEquals(case.returned, date?.latestIso, case.text)
            }
        }
    }

    // --- FT-070 structured client errors -------------------------------------------------

    /**
     * A fresh tree owned by owner B with two partners, their union and an unlinked
     * child, so these tests never change the rows the other tests assert on.
     */
    private data class Family(val tree: String, val father: String, val mother: String, val child: String, val union: String)

    private suspend fun ApplicationTestBuilder.newFamily(): Family {
        val tree = client.createTree(token(Actor.OWNER_B), "FT-070 family").id
        val t = path(tree)
        suspend fun person(given: String) =
            client.write("$t/people", Actor.OWNER_B, encode(CreatePersonRequest(givenNames = given, sex = "UNKNOWN", isLiving = false)))
                .decoded<CreatePersonResponse>(HttpStatusCode.Created).person.id
        val father = person("Mateo")
        val mother = person("Julia")
        val child = person("Ines")
        val union = client.write("$t/unions", Actor.OWNER_B, encode(CreateUnionRequest(father, mother, type = "MARRIED")))
            .decoded<UnionDto>(HttpStatusCode.Created).id
        return Family(tree, father, mother, child, union)
    }

    /** Row counts of every tenant table and the audit log for one tree. */
    private fun countsFor(treeId: String): Map<String, Long> =
        tables.associateWith { table -> dataSource.query("select count(*) from $table where tree_id = ?", uuid(treeId))!!.toLong() }

    private fun childLinks(family: Family): Long =
        dataSource.query(
            "select count(*) from union_child where union_id = ? and child_id = ?", uuid(family.union), uuid(family.child),
        )!!.toLong()

    private fun childLinkAudits(family: Family): Long =
        dataSource.query(
            "select count(*) from audit_log where tree_id = ? and entity_type = 'UnionChild' and entity_id = ?",
            uuid(family.tree), "${family.union}:${family.child}",
        )!!.toLong()

    private suspend fun assertStructured400(response: HttpResponse, message: String, context: String, raw: String? = null) {
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.BadRequest, response.status, "$context: $body")
        val error = json.decodeFromString<ApiErrorResponse>(body)
        assertEquals(message, error.message, context)
        assertFalse("Exception" in body, "$context exposed exception text: $body")
        raw?.let { assertFalse(it in body, "$context echoed the malformed value: $body") }
    }

    @Test
    fun `malformed IDs, unknown enum values and invalid JSON are structured 400s that change nothing`() = api {
        val f = newFamily()
        val t = path(f.tree)
        val bad = "not-a-uuid"
        data class Case(val name: String, val path: String, val body: String?, val message: String, val raw: String? = null)
        fun id(field: String) = "$field must be a UUID"
        val cases = listOf(
            // Path IDs.
            Case("tree", path(bad), null, id("treeId"), bad),
            Case("tree of a list", "${path(bad)}/people", null, id("treeId"), bad),
            Case("tree of a write", "${path(bad)}/places", encode(CreatePlaceRequest("Cusco")), id("treeId"), bad),
            Case("person", "$t/people/$bad", null, id("personId"), bad),
            Case("ancestors anchor", "$t/people/$bad/ancestors", null, id("personId"), bad),
            Case("descendants anchor", "$t/people/$bad/descendants", null, id("personId"), bad),
            Case("events subject", "$t/people/$bad/events", null, id("personId"), bad),
            Case("union of a child link", "$t/unions/$bad/children", encode(AddUnionChildRequest(f.child, "BIRTH")), id("unionId"), bad),
            // Body IDs.
            Case("first partner", "$t/unions", encode(CreateUnionRequest(bad, type = "MARRIED")), id("partner1Id"), bad),
            Case("second partner", "$t/unions", encode(CreateUnionRequest(f.father, bad, type = "MARRIED")), id("partner2Id"), bad),
            Case("child", "$t/unions/${f.union}/children", encode(AddUnionChildRequest(bad, "BIRTH")), id("childId"), bad),
            Case("event person", "$t/events", encode(CreateEventRequest(personId = bad, type = "BIRTH")), id("personId"), bad),
            Case("event union", "$t/events", encode(CreateEventRequest(unionId = bad, type = "MARRIAGE")), id("unionId"), bad),
            Case(
                "event place", "$t/events", encode(CreateEventRequest(personId = f.father, type = "BIRTH", placeId = bad)),
                id("placeId"), bad,
            ),
            // Enum values.
            Case(
                "sex", "$t/people", encode(CreatePersonRequest(givenNames = "Pablo", sex = "ROBOT", isLiving = false)),
                "Unknown sex 'ROBOT'",
            ),
            Case("union type", "$t/unions", encode(CreateUnionRequest(f.father, f.mother, type = "ROBOT")), "Unknown union type 'ROBOT'"),
            Case(
                "child relation", "$t/unions/${f.union}/children", encode(AddUnionChildRequest(f.child, "ROBOT")),
                "Unknown child relation 'ROBOT'",
            ),
            Case("event type", "$t/events", encode(CreateEventRequest(personId = f.father, type = "ROBOT")), "Unknown event type 'ROBOT'"),
            Case("source type", "$t/sources", encode(CreateSourceRequest(title = "Census", type = "ROBOT")), "Unknown source type 'ROBOT'"),
            // Bodies that can't be decoded: syntax, a missing required field, a wrong JSON type.
            Case("people syntax", "$t/people", "{ \"givenNames\": \"secret-body\"", MALFORMED_BODY_MESSAGE, "secret-body"),
            Case("unions syntax", "$t/unions", "{ not json", MALFORMED_BODY_MESSAGE),
            Case("child link syntax", "$t/unions/${f.union}/children", "{ not json", MALFORMED_BODY_MESSAGE),
            Case("events syntax", "$t/events", "[", MALFORMED_BODY_MESSAGE),
            Case("places syntax", "$t/places", "{\"name\":", MALFORMED_BODY_MESSAGE),
            Case("sources syntax", "$t/sources", "{ not json", MALFORMED_BODY_MESSAGE),
            Case("trees syntax", "/api/v1/trees", "{ not json", MALFORMED_BODY_MESSAGE),
            Case("missing field", "$t/people", "{\"givenNames\":\"Pablo\"}", MALFORMED_BODY_MESSAGE),
            Case("wrong type", "$t/people", "{\"givenNames\":\"Pablo\",\"sex\":\"MALE\",\"isLiving\":\"secret-flag\"}", MALFORMED_BODY_MESSAGE, "secret-flag"),
        )
        val before = countsFor(f.tree)
        for (case in cases) {
            val response = if (case.body == null) client.read(case.path, Actor.OWNER_B) else client.write(case.path, Actor.OWNER_B, case.body)
            assertStructured400(response, case.message, case.name, case.raw)
        }
        assertEquals(before, countsFor(f.tree))
    }

    @Test
    fun `an unsupported content type stays a 415`() = api {
        val f = newFamily()
        val response = client.request("${path(f.tree)}/places") {
            method = HttpMethod.Post
            bearerAuth(token(Actor.OWNER_B))
            contentType(ContentType.Text.Plain)
            setBody("Cusco")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, response.bodyAsText())
    }

    @Test
    fun `well-formed IDs that don't exist stay generic 404s`() = api {
        val f = newFamily()
        val t = path(f.tree)
        val missing = randomId()
        val before = countsFor(f.tree)
        assertError(client.read("$t/people/$missing", Actor.OWNER_B), HttpStatusCode.NotFound, "No person with id $missing", "person")
        assertError(
            client.write("$t/unions/$missing/children", Actor.OWNER_B, encode(AddUnionChildRequest(f.child, "BIRTH"))),
            HttpStatusCode.NotFound, "No union with id $missing", "union",
        )
        // PR #3: the union is resolved before the body's child ID is parsed.
        assertError(
            client.write("$t/unions/$missing/children", Actor.OWNER_B, encode(AddUnionChildRequest("not-a-uuid", "BIRTH"))),
            HttpStatusCode.NotFound, "No union with id $missing", "missing union, malformed child",
        )
        assertError(
            client.write("$t/unions/${f.union}/children", Actor.OWNER_B, encode(AddUnionChildRequest(missing, "BIRTH"))),
            HttpStatusCode.NotFound, "No person with id $missing", "child",
        )
        assertError(
            client.write("$t/events", Actor.OWNER_B, encode(CreateEventRequest(personId = f.father, type = "BIRTH", placeId = missing))),
            HttpStatusCode.NotFound, "No place with id $missing", "event place",
        )
        assertEquals(before, countsFor(f.tree))
    }

    @Test
    fun `a member without an editing grant gets 403 before any ID or body validation`() = api {
        val a = path(treeA.id)
        val noEditAccess = "Your access to tree ${treeA.id} doesn't allow changes"
        val before = rowCounts()
        assertError(
            client.write("$a/unions/not-a-uuid/children", Actor.RELATIVE, encode(AddUnionChildRequest(antonio, "BIRTH"))),
            HttpStatusCode.Forbidden, noEditAccess, "malformed union",
        )
        assertError(
            client.write("$a/unions", Actor.RELATIVE, encode(CreateUnionRequest("not-a-uuid", type = "ROBOT"))),
            HttpStatusCode.Forbidden, noEditAccess, "malformed partner and type",
        )
        assertEquals(before, rowCounts())
    }

    @Test
    fun `two spellings of one partner ID are the same person`() = api {
        val f = newFamily()
        val before = countsFor(f.tree)
        val response = client.write(
            "${path(f.tree)}/unions", Actor.OWNER_B, encode(CreateUnionRequest(f.father, f.father.uppercase(), type = "MARRIED")),
        )
        assertStructured400(response, "A union's two partners must be different people", "same partner")
        assertEquals(before, countsFor(f.tree))
    }

    @Test
    fun `repeating a child link is a 409 whatever the ID spelling, leaving one link and one audit row`() = api {
        val f = newFamily()
        val t = path(f.tree)
        client.write("$t/unions/${f.union}/children", Actor.OWNER_B, encode(AddUnionChildRequest(f.child, "BIRTH")))
            .decoded<UnionDto>(HttpStatusCode.Created)
        val before = countsFor(f.tree)
        val conflict = "This person is already a child of union ${f.union}"
        val repeats = listOf(
            "same spelling" to ("$t/unions/${f.union}/children" to AddUnionChildRequest(f.child, "BIRTH")),
            "other relation" to ("$t/unions/${f.union}/children" to AddUnionChildRequest(f.child, "ADOPTED")),
            "upper-case child" to ("$t/unions/${f.union}/children" to AddUnionChildRequest(f.child.uppercase(), "BIRTH")),
            "upper-case union" to ("$t/unions/${f.union.uppercase()}/children" to AddUnionChildRequest(f.child, "BIRTH")),
        )
        for ((name, request) in repeats) {
            val response = client.write(request.first, Actor.OWNER_B, encode(request.second))
            assertError(response, HttpStatusCode.Conflict, conflict, name)
            assertAbsent(response.bodyAsText(), listOf("union_child_pkey", "duplicate key", "Exception", "SQL"), name)
        }
        assertEquals(before, countsFor(f.tree))
        assertEquals(1, childLinks(f))
        assertEquals(1, childLinkAudits(f))
    }

    @Test
    fun `concurrent duplicate child links create one link and one audit row`() = api {
        val f = newFamily()
        val linkPath = "${path(f.tree)}/unions/${f.union}/children"
        val body = encode(AddUnionChildRequest(f.child, "BIRTH"))
        val responses = coroutineScope {
            List(8) { async { client.write(linkPath, Actor.OWNER_B, body) } }.awaitAll()
        }
        val statuses = responses.map { it.status }
        assertEquals(1, statuses.count { it == HttpStatusCode.Created }, "statuses: $statuses")
        for (response in responses.filter { it.status != HttpStatusCode.Created }) {
            assertError(response, HttpStatusCode.Conflict, "This person is already a child of union ${f.union}", "concurrent")
        }
        assertEquals(1, childLinks(f))
        assertEquals(1, childLinkAudits(f))
    }
}
