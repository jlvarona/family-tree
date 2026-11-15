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
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.module
import com.moxietech.family_tree.server.config.ApiErrorResponse
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DEFAULT_API_AUDIENCE
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.config.OidcSettings
import com.moxietech.family_tree.server.config.TestJwks
import com.moxietech.family_tree.server.identity.IdentityTestApi.bodyWithStatus
import com.moxietech.family_tree.server.identity.IdentityTestApi.createTree
import com.moxietech.family_tree.server.identity.IdentityTestApi.me
import com.moxietech.family_tree.server.identity.IdentityTestApi.treeIds
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
            "$a/people/$carmen/events", "$a/unions", "$a/places", "$a/sources",
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
        // Phase 1: a member can't create people, so the duplicate check never runs for them (FT-068).
        val asRelative = client.write("$a/people", Actor.RELATIVE, request)
        assertError(asRelative, HttpStatusCode.Forbidden, "Only the tree owner can make changes to tree ${treeA.id}", "relative create")
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
    fun `an active non-owner gets 403 for every write, valid or malformed, and nothing changes`() = api {
        val ownerOnly = "Only the tree owner can make changes to tree ${treeA.id}"
        val before = rowCounts()
        for ((path, body) in writesToTreeA()) {
            assertError(client.write(path, Actor.RELATIVE, body), HttpStatusCode.Forbidden, ownerOnly, "valid $path")
            assertError(client.write(path, Actor.RELATIVE, "{ not json"), HttpStatusCode.Forbidden, ownerOnly, "malformed $path")
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
}
