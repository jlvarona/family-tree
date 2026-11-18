package com.moxietech.family_tree.server.permissions

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CitationDto
import com.moxietech.family_tree.api.CreateCitationRequest
import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.CreateGrantRequest
import com.moxietech.family_tree.api.CreateInvitationRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePersonResponse
import com.moxietech.family_tree.api.CreatePlaceRequest
import com.moxietech.family_tree.api.CreateSourceRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.CreatedInvitationResponse
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.GrantDto
import com.moxietech.family_tree.api.RedeemInvitationRequest
import com.moxietech.family_tree.api.RedeemInvitationResponse
import com.moxietech.family_tree.api.InvitationGrantRequest
import com.moxietech.family_tree.api.MergePeopleRequest
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.SetProfilePhotoRequest
import com.moxietech.family_tree.api.SourceDto
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.api.UpdateEventRequest
import com.moxietech.family_tree.api.UpdateGrantRequest
import com.moxietech.family_tree.api.UpdatePersonRequest
import com.moxietech.family_tree.api.UpdateUnionChildRequest
import com.moxietech.family_tree.api.UpdateUnionRequest
import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.GrantId
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.permissions.GrantRules
import com.moxietech.family_tree.server.config.ApiErrorResponse
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.config.configureErrorMapping
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.graph.PersonService
import com.moxietech.family_tree.server.graph.UnionService
import com.moxietech.family_tree.server.graph.eventRoutes
import com.moxietech.family_tree.server.graph.personRoutes
import com.moxietech.family_tree.server.graph.placeRoutes
import com.moxietech.family_tree.server.graph.unionRoutes
import com.moxietech.family_tree.server.identity.AccountRepository
import com.moxietech.family_tree.server.identity.IdentityTestApi.query
import com.moxietech.family_tree.server.identity.OidcIdentity
import com.moxietech.family_tree.server.sources.citationRoutes
import com.moxietech.family_tree.server.sources.sourceRoutes
import com.moxietech.family_tree.server.trees.INVITATION_NOT_FOUND_MESSAGE
import com.moxietech.family_tree.server.trees.InvitationService
import com.moxietech.family_tree.server.trees.InvitationSettings
import com.moxietech.family_tree.server.trees.MemberService
import com.moxietech.family_tree.server.trees.MembershipRepository
import com.moxietech.family_tree.server.trees.invitationRoutes
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/**
 * FT-012: grant-based authorization of every person, relationship, event and citation
 * write, plus the owner/admin policy for tree-wide records and the owner-only
 * operations, over HTTP through the production routes against PostgreSQL 16. The
 * action / role / target rules themselves are tabulated in :core `EditAuthorityTest`;
 * these cases show the server applies them to trusted same-tree data inside the write,
 * and that a refusal writes nothing (no row, no audit entry, no generation advance).
 *
 * Every test builds its own tree (people marked * are living):
 *
 *   gp1 + gp2 (1940)                    ->  mum, aunt
 *   mum + dad* (1975)                   ->  me, sib
 *   aunt + uncle                        ->  cousin
 *   out1 + out2                         ->  outKid*          (another family)
 *   loner                                                    (no relationships)
 *
 * Members (the owner created every person):
 * - CONTRIBUTOR, linked to `me` (home branch: mum, dad, me, sib), with a CONTRIBUTOR
 *   grant on aunt's descendants with partners (aunt, uncle, cousin).
 * - AUNT, linked to `aunt` (so aunt has an account), no grants.
 * - VIEWER, linked to `sib`, VIEWER over the whole tree.
 * - BRANCH_OWNER, unlinked, BRANCH_OWNER on aunt's descendants with partners.
 * - ADMIN and ADMIN2, ADMIN over the whole tree.
 * - LIMITED_ADMIN, an ADMIN grant on gp1's side stored directly (the API now refuses it).
 * - INVITEE, INVITED membership with a CONTRIBUTOR whole-tree grant.
 * - OTHER_OWNER owns another tree and isn't a member here.
 * - NEWCOMER and NEWCOMER2 aren't members; they redeem invitations.
 *
 * Linked members need an explicit CONTRIBUTOR or higher grant to edit their home branch
 * (decisions table, "Grant roles (FT-012)"): AUNT (linked, no grant) and VIEWER (linked,
 * VIEWER only) stay read-only.
 */
class GrantAuthorizationTest {

    private enum class Actor(val email: String) {
        OWNER("ft012-owner@example.com"),
        CONTRIBUTOR("ft012-contributor@example.com"),
        AUNT("ft012-aunt@example.com"),
        VIEWER("ft012-viewer@example.com"),
        BRANCH_OWNER("ft012-branch-owner@example.com"),
        ADMIN("ft012-admin@example.com"),
        ADMIN2("ft012-admin2@example.com"),
        NEWCOMER("ft012-newcomer@example.com"),
        NEWCOMER2("ft012-newcomer2@example.com"),
        LIMITED_ADMIN("ft012-limited-admin@example.com"),
        INVITEE("ft012-invitee@example.com"),
        OTHER_OWNER("ft012-other-owner@example.com"),
    }

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val TEST_SECRET = "grant-authorization-test-only"
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
    }

    private val Actor.id get() = accounts.getValue(this).id
    private val Actor.uuid get() = Uuid.parse(id.value)

    private class Family(val tree: String) {
        val people = linkedMapOf<String, String>()
        val unions = linkedMapOf<String, String>()
        val grants = linkedMapOf<Actor, String>()
        fun id(label: String) = people.getValue(label)
        fun union(label: String) = unions.getValue(label)
        val path get() = "/api/v1/trees/$tree"
        val treeId get() = TreeId(tree)
        val treeUuid get() = Uuid.parse(tree)
    }

    private suspend fun newFamily(): Family {
        val owner = Actor.OWNER.id
        val f = Family(TreeService.createTree(owner, "FT-012 family").id.value)
        val living = setOf("dad", "outKid")
        for (label in listOf("gp1", "gp2", "mum", "aunt", "dad", "uncle", "me", "sib", "cousin", "out1", "out2", "outKid", "loner")) {
            f.people[label] = PersonService.createPerson(
                f.treeId, owner, CreatePersonRequest(givenNames = label, sex = "UNKNOWN", isLiving = label in living),
            ).first.id.value
        }
        suspend fun union(label: String, a: String, b: String, start: String?, vararg kids: String) {
            val union = UnionService.createUnion(f.treeId, owner, CreateUnionRequest(f.id(a), f.id(b), "MARRIED", startDate = start))
            f.unions[label] = union.id
            for (kid in kids) UnionService.addChild(f.treeId, UnionId(union.id), owner, AddUnionChildRequest(f.id(kid), "BIRTH"))
        }
        union("G", "gp1", "gp2", "1940", "mum", "aunt")
        union("P", "mum", "dad", "1975", "me", "sib")
        union("A", "aunt", "uncle", null, "cousin")
        union("O", "out1", "out2", null, "outKid")

        fun member(actor: Actor, person: String? = null, status: MembershipStatus = MembershipStatus.ACTIVE) =
            MembershipRepository.insert(f.treeUuid, actor.uuid, person?.let { Uuid.parse(f.id(it)) }, status)
        suspend fun grant(actor: Actor, role: String, anchor: String, direction: String, partners: Boolean = false) {
            f.grants[actor] = GrantService.createGrant(f.treeId, owner, CreateGrantRequest(actor.id.value, role, f.id(anchor), direction, includePartners = partners)).id
        }
        member(Actor.CONTRIBUTOR, "me")
        grant(Actor.CONTRIBUTOR, "CONTRIBUTOR", "aunt", "DESCENDANTS", partners = true)
        member(Actor.AUNT, "aunt")
        member(Actor.VIEWER, "sib")
        grant(Actor.VIEWER, "VIEWER", "gp1", "WHOLE_TREE")
        member(Actor.BRANCH_OWNER)
        grant(Actor.BRANCH_OWNER, "BRANCH_OWNER", "aunt", "DESCENDANTS", partners = true)
        member(Actor.ADMIN)
        grant(Actor.ADMIN, "ADMIN", "gp1", "WHOLE_TREE")
        member(Actor.ADMIN2)
        grant(Actor.ADMIN2, "ADMIN", "gp1", "WHOLE_TREE")
        member(Actor.LIMITED_ADMIN)
        // Stored before FT-012 could refuse it: an ADMIN grant on a limited branch.
        dataSource.connection.use { c ->
            c.prepareStatement(
                "insert into tree_grant (id, tree_id, account_id, role, anchor_person_id, direction, include_partners, created_by_account_id) " +
                    "values (gen_random_uuid(), ?, ?, 'ADMIN', ?, 'SIDE', true, ?)",
            ).use { st ->
                st.setObject(1, UUID.fromString(f.tree))
                st.setObject(2, UUID.fromString(Actor.LIMITED_ADMIN.id.value))
                st.setObject(3, UUID.fromString(f.id("gp1")))
                st.setObject(4, UUID.fromString(owner.value))
                st.executeUpdate()
            }
        }
        member(Actor.INVITEE, status = MembershipStatus.INVITED)
        grant(Actor.INVITEE, "CONTRIBUTOR", "gp1", "WHOLE_TREE")
        return f
    }

    private fun invitationSettings(enabled: Boolean) = InvitationSettings(
        enabled = enabled,
        defaultTtl = Duration.ofHours(168),
        maxTtl = Duration.ofHours(720),
        mailTransport = InvitationSettings.MailTransport.NONE,
        mailCaptureDir = null,
        acceptUrl = "http://localhost:3000/",
        mailFrom = "invitations@family-tree.invalid",
    )

    /**
     * The production routes under test. [management] is the value of the invitation gate
     * (`INVITATIONS_ENABLED`): invitation, member and grant routes are installed only when
     * it is true, exactly as in `Application.module`.
     */
    private fun api(management: Boolean = false, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
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
                unionRoutes()
                eventRoutes()
                placeRoutes()
                sourceRoutes()
                citationRoutes()
                val settings = invitationSettings(management)
                invitationRoutes(InvitationService(settings), MemberService(settings))
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

    private suspend inline fun <reified T> HttpResponse.decoded(status: HttpStatusCode = HttpStatusCode.OK): T {
        val body = bodyAsText()
        assertEquals(status, this.status, body)
        return json.decodeFromString(body)
    }

    private suspend fun HttpResponse.expectError(status: HttpStatusCode, message: String, context: String) {
        val body = bodyAsText()
        assertEquals(status, this.status, "$context: $body")
        assertEquals(message, json.decodeFromString<ApiErrorResponse>(body).message, context)
    }

    private fun outside(f: Family) = WriteAccess.outsideBranchMessage(f.tree)
    private fun widens(f: Family) = WriteAccess.widensReachMessage(f.tree)
    private fun noEdit(f: Family) = TreeService.noEditAccessMessage(f.tree)
    private fun treeWide(f: Family) = TreeService.treeWideOnlyMessage(f.tree)
    private fun ownerOnly(f: Family) = TreeService.ownerOnlyMessage(f.tree)
    private fun managerOnly(f: Family) = TreeService.managerOnlyMessage(f.tree)
    private fun grantCount(f: Family): Long = count("select count(*) from tree_grant where tree_id = ?", UUID.fromString(f.tree))
    private fun notMember(f: Family) = "Not a member of tree ${f.tree}"

    private fun count(sql: String, vararg args: Any?): Long = dataSource.query(sql, *args)!!.toLong()

    /** Everything a graph, event, citation or audit write can change in [f]'s tree. */
    private fun fingerprint(f: Family): List<Long> {
        val tree = UUID.fromString(f.tree)
        return listOf(
            "select count(*) from person where tree_id = ?",
            "select coalesce(sum(version), 0) from person where tree_id = ?",
            "select count(*) from union_record where tree_id = ?",
            "select coalesce(sum(version), 0) from union_record where tree_id = ?",
            "select count(*) from union_child where tree_id = ?",
            "select coalesce(sum(version), 0) from union_child where tree_id = ?",
            "select count(*) from event where tree_id = ?",
            "select coalesce(sum(version), 0) from event where tree_id = ?",
            "select count(*) from citation where tree_id = ?",
            "select count(*) from place where tree_id = ?",
            "select count(*) from source where tree_id = ?",
            "select count(*) from audit_log where tree_id = ?",
            "select generation from tree_graph_generation where tree_id = ?",
        ).map { count(it, tree) }
    }

    private fun auditsBy(f: Family, actor: Actor): Long =
        count("select count(*) from audit_log where tree_id = ? and actor_account_id = ?", UUID.fromString(f.tree), UUID.fromString(actor.id.value))

    private suspend fun HttpClient.person(f: Family, label: String): PersonDto =
        send(HttpMethod.Get, "${f.path}/people/${f.id(label)}", Actor.OWNER).decoded()

    private suspend fun HttpClient.unionsAsOwner(f: Family): List<UnionDto> =
        send(HttpMethod.Get, "${f.path}/unions", Actor.OWNER).decoded()

    private suspend fun HttpClient.renamePerson(f: Family, id: String, actor: Actor, name: String): HttpResponse {
        val current: PersonDto = send(HttpMethod.Get, "${f.path}/people/$id", Actor.OWNER).decoded()
        return send(
            HttpMethod.Put,
            "${f.path}/people/$id",
            actor,
            encode(UpdatePersonRequest(givenNames = name, sex = "UNKNOWN", isLiving = current.isLiving, version = current.version)),
        )
    }

    // --- The role table on one action (person update) --------------------------------

    @Test
    fun `person updates follow each role's reach, and every refusal writes nothing`() = api {
        val f = newFamily()
        val live = mapOf(
            Actor.OWNER to listOf("gp1", "aunt", "me", "out1", "outKid", "loner"),
            Actor.ADMIN to listOf("gp1", "aunt", "me", "out1", "outKid", "loner"),
            // Home branch (accounts included: sib is the viewer's person), then the
            // contributor grant's people without an account.
            Actor.CONTRIBUTOR to listOf("me", "sib", "mum", "dad", "cousin", "uncle"),
            Actor.BRANCH_OWNER to listOf("aunt", "uncle", "cousin"),
        )
        val refused = mapOf(
            Actor.CONTRIBUTOR to listOf("aunt", "gp1", "gp2", "out1", "outKid", "loner").map { it to outside(f) },
            Actor.BRANCH_OWNER to listOf("me", "mum", "out1").map { it to outside(f) },
            Actor.VIEWER to listOf("sib", "out1").map { it to noEdit(f) },
            Actor.AUNT to listOf("aunt", "cousin").map { it to noEdit(f) },
            Actor.LIMITED_ADMIN to listOf("gp1", "mum", "out1").map { it to noEdit(f) },
            Actor.INVITEE to listOf("cousin").map { it to notMember(f) },
            Actor.OTHER_OWNER to listOf("cousin").map { it to notMember(f) },
        )
        for ((actor, labels) in live) {
            for (label in labels) {
                val before = auditsBy(f, actor)
                val response = client.renamePerson(f, f.id(label), actor, "$label by ${actor.name}")
                assertEquals(HttpStatusCode.OK, response.status, "$actor $label: ${response.bodyAsText()}")
                assertEquals("$label by ${actor.name}", client.person(f, label).givenNames, "$actor $label")
                assertEquals(before + 1, auditsBy(f, actor), "$actor $label audited once, as the actor")
            }
        }
        for ((actor, cases) in refused) {
            for ((label, message) in cases) {
                val before = fingerprint(f)
                client.renamePerson(f, f.id(label), actor, "nope").expectError(HttpStatusCode.Forbidden, message, "$actor $label")
                assertEquals(before, fingerprint(f), "$actor $label wrote nothing")
            }
        }
    }

    @Test
    fun `a write response follows living-person privacy for the writer`() = api {
        val f = newFamily()
        // dad is living, in the contributor's home branch but hidden to them until FT-013.
        val response = client.renamePerson(f, f.id("dad"), Actor.CONTRIBUTOR, "Papá").decoded<PersonDto>()
        assertEquals("Private", response.fullName)
        assertEquals("Papá", client.person(f, "dad").givenNames)
    }

    // --- Tree-wide records and owner-only operations -------------------------------

    @Test
    fun `places and sources need the owner or a whole-tree admin`() = api {
        val f = newFamily()
        for (actor in listOf(Actor.OWNER, Actor.ADMIN)) {
            assertEquals(HttpStatusCode.Created, client.send(HttpMethod.Post, "${f.path}/places", actor, encode(CreatePlaceRequest("Lima $actor"))).status)
            assertEquals(
                HttpStatusCode.Created,
                client.send(HttpMethod.Post, "${f.path}/sources", actor, encode(CreateSourceRequest(title = "Census $actor", type = "CENSUS"))).status,
            )
        }
        val before = fingerprint(f)
        for ((actor, message) in listOf(
            Actor.CONTRIBUTOR to treeWide(f),
            Actor.BRANCH_OWNER to treeWide(f),
            Actor.VIEWER to noEdit(f),
            Actor.LIMITED_ADMIN to noEdit(f),
            Actor.INVITEE to notMember(f),
        )) {
            for (body in listOf(encode(CreatePlaceRequest("Cádiz")), "{ not json")) {
                client.send(HttpMethod.Post, "${f.path}/places", actor, body).expectError(HttpStatusCode.Forbidden, message, "$actor place $body")
            }
            client.send(HttpMethod.Post, "${f.path}/sources", actor, encode(CreateSourceRequest(title = "x", type = "CENSUS")))
                .expectError(HttpStatusCode.Forbidden, message, "$actor source")
        }
        assertEquals(before, fingerprint(f))
        // The media upload gate is the same rule (the routes need an object store; MediaRoutesTest).
        TreeService.getTreeForTreeWideWriter(f.treeId, Actor.ADMIN.id)
        assertEquals(treeWide(f), assertFailsWith<ForbiddenException> { TreeService.getTreeForTreeWideWriter(f.treeId, Actor.CONTRIBUTOR.id) }.message)
    }

    @Test
    fun `merges stay with the owner, while grant writes take the owner or a whole-tree admin`() = api {
        val f = newFamily()
        val before = fingerprint(f)
        val merge = encode(MergePeopleRequest(f.id("loner"), 1, f.id("out1"), 1, emptyMap()))
        for (actor in listOf(Actor.ADMIN, Actor.CONTRIBUTOR, Actor.BRANCH_OWNER)) {
            client.send(HttpMethod.Post, "${f.path}/people/merge", actor, merge).expectError(HttpStatusCode.Forbidden, ownerOnly(f), "$actor merge")
        }
        val request = CreateGrantRequest(Actor.AUNT.id.value, "BRANCH_OWNER", f.id("aunt"), "DESCENDANTS")
        for ((actor, message) in listOf(
            Actor.CONTRIBUTOR to managerOnly(f),
            Actor.BRANCH_OWNER to managerOnly(f),
            Actor.VIEWER to noEdit(f),
            Actor.LIMITED_ADMIN to noEdit(f),
            Actor.INVITEE to notMember(f),
        )) {
            val error = assertFailsWith<ForbiddenException>("$actor grant") { GrantService.createGrant(f.treeId, actor.id, request) }
            assertEquals(message, error.message, "$actor grant")
        }
        assertEquals(before, fingerprint(f), "no merge, grant or audit entry was written")
        val grantsBefore = grantCount(f)

        val created = GrantService.createGrant(f.treeId, Actor.ADMIN.id, request)
        assertEquals(grantsBefore + 1, grantCount(f))
        assertEquals(Actor.ADMIN.id.value, dataSource.query("select created_by_account_id from tree_grant where id = ?", UUID.fromString(created.id)))
        assertEquals(
            "1",
            dataSource.query(
                "select count(*) from audit_log where tree_id = ? and entity_type = 'Grant' and entity_id = ? and actor_account_id = ?",
                UUID.fromString(f.tree), created.id, UUID.fromString(Actor.ADMIN.id.value),
            ),
        )
    }

    @Test
    fun `an admin grant must cover the whole tree, and one stored on a limited branch carries nothing`() = runBlocking {
        val f = newFamily()
        val owner = Actor.OWNER.id
        val grantsBefore = count("select count(*) from tree_grant where tree_id = ?", UUID.fromString(f.tree))
        for (direction in listOf("SIDE", "ANCESTORS", "DESCENDANTS")) {
            val error = assertFailsWith<ValidationException>(direction) {
                GrantService.createGrant(f.treeId, owner, CreateGrantRequest(Actor.AUNT.id.value, "ADMIN", f.id("gp1"), direction))
            }
            assertEquals(listOf(GrantRules.ADMIN_SCOPE_MESSAGE), error.errors)
        }
        val viewerGrant = f.grants.getValue(Actor.VIEWER)
        assertFailsWith<ValidationException> {
            GrantService.updateGrant(f.treeId, GrantId(viewerGrant), owner, UpdateGrantRequest("ADMIN", f.id("gp1"), "SIDE", version = 1))
        }
        val invitations = InvitationService(invitationSettings(enabled = true))
        val invitationError = assertFailsWith<ValidationException> {
            invitations.create(
                f.treeId,
                accounts.getValue(Actor.OWNER),
                CreateInvitationRequest(grants = listOf(InvitationGrantRequest("ADMIN", f.id("gp1"), "DESCENDANTS", depth = 2))),
            )
        }
        assertEquals(listOf(GrantRules.ADMIN_SCOPE_MESSAGE), invitationError.errors)
        assertEquals(0L, count("select count(*) from invitation where tree_id = ?", UUID.fromString(f.tree)))
        assertEquals(grantsBefore, count("select count(*) from tree_grant where tree_id = ?", UUID.fromString(f.tree)))
        // A whole-tree ADMIN grant is still accepted.
        GrantService.createGrant(f.treeId, owner, CreateGrantRequest(Actor.AUNT.id.value, "ADMIN", f.id("gp1"), "WHOLE_TREE"))
        Unit
    }

    // --- Relationships: at least one end, and no widening of one's own reach ---------

    @Test
    fun `a contributor can't pull existing people into their branch by reparenting or retargeting`() = api {
        val f = newFamily()
        val before = fingerprint(f)
        val unionsBefore = client.unionsAsOwner(f)
        // out1 (and their descendants) under the contributor's parents.
        client.send(HttpMethod.Post, "${f.path}/unions/${f.union("P")}/children", Actor.CONTRIBUTOR, encode(AddUnionChildRequest(f.id("out1"), "ADOPTED")))
            .expectError(HttpStatusCode.Forbidden, widens(f), "adopt out1")
        // out2 as the partner of someone in the home branch (partners are included).
        client.send(HttpMethod.Post, "${f.path}/unions", Actor.CONTRIBUTOR, encode(CreateUnionRequest(f.id("me"), f.id("out2"), "PARTNERS")))
            .expectError(HttpStatusCode.Forbidden, widens(f), "partner out2")
        // Swapping a home partner for an outsider: allowed ends, but it widens the branch.
        val p = unionsBefore.single { it.id == f.union("P") }
        client.send(
            HttpMethod.Put, "${f.path}/unions/${p.id}", Actor.CONTRIBUTOR,
            encode(UpdateUnionRequest(f.id("mum"), f.id("out1"), "MARRIED", startDate = "1975", version = p.version)),
        ).expectError(HttpStatusCode.Forbidden, widens(f), "swap dad for out1")
        // Moving a union entirely outside: the replacement has no end the caller may edit.
        val a = unionsBefore.single { it.id == f.union("A") }
        client.send(
            HttpMethod.Put, "${f.path}/unions/${a.id}", Actor.CONTRIBUTOR,
            encode(UpdateUnionRequest(f.id("aunt"), f.id("out1"), "MARRIED", version = a.version)),
        ).expectError(HttpStatusCode.Forbidden, outside(f), "swap uncle for out1")
        // No row, version, audit entry or graph generation changed.
        assertEquals(before, fingerprint(f))
        val after = client.unionsAsOwner(f).associate { it.id to (it.partner1Id to it.partner2Id) }
        assertEquals(unionsBefore.associate { it.id to (it.partner1Id to it.partner2Id) }, after)
    }

    @Test
    fun `a contributor adds a new relative, attaches them to their branch and keeps editing them`() = api {
        val f = newFamily()
        val created = client.send(HttpMethod.Post, "${f.path}/people", Actor.CONTRIBUTOR, encode(CreatePersonRequest(givenNames = "Baby", sex = "UNKNOWN", isLiving = false)))
            .decoded<CreatePersonResponse>(HttpStatusCode.Created).person
        assertEquals(Actor.CONTRIBUTOR.id.value, dataSource.query("select created_by_account_id from person where id = ?", UUID.fromString(created.id)))
        assertEquals(HttpStatusCode.OK, client.renamePerson(f, created.id, Actor.CONTRIBUTOR, "Bebé").status, "unattached and theirs")
        // Someone else can't edit it while it is unattached.
        client.renamePerson(f, created.id, Actor.BRANCH_OWNER, "no").expectError(HttpStatusCode.Forbidden, outside(f), "branch owner")

        val linked = client.send(HttpMethod.Post, "${f.path}/unions/${f.union("P")}/children", Actor.CONTRIBUTOR, encode(AddUnionChildRequest(created.id, "BIRTH")))
            .decoded<UnionDto>(HttpStatusCode.Created)
        assertNotNull(linked.children.singleOrNull { it.childId == created.id })
        // dad is living and hidden from the contributor (FT-066): the union keeps its links, not its date.
        assertNull(linked.startDate)
        assertNotNull(client.unionsAsOwner(f).single { it.id == f.union("P") }.startDate)
        assertEquals(HttpStatusCode.OK, client.renamePerson(f, created.id, Actor.CONTRIBUTOR, "Bebé 2").status, "now in the home branch")
        assertEquals(4L, auditsBy(f, Actor.CONTRIBUTOR), "create, rename, link, rename: each audited as the contributor")

        // A person the contributor created but the owner attached elsewhere is no longer theirs.
        val draft = client.send(HttpMethod.Post, "${f.path}/people", Actor.CONTRIBUTOR, encode(CreatePersonRequest(givenNames = "Draft", sex = "UNKNOWN", isLiving = false)))
            .decoded<CreatePersonResponse>(HttpStatusCode.Created).person
        assertEquals(
            HttpStatusCode.Created,
            client.send(HttpMethod.Post, "${f.path}/unions/${f.union("O")}/children", Actor.OWNER, encode(AddUnionChildRequest(draft.id, "BIRTH"))).status,
        )
        client.renamePerson(f, draft.id, Actor.CONTRIBUTOR, "mine?").expectError(HttpStatusCode.Forbidden, outside(f), "attached elsewhere")
    }

    @Test
    fun `relationship writes need live access to at least one of their people`() = api {
        val f = newFamily()
        val o = client.unionsAsOwner(f).single { it.id == f.union("O") }
        val outKid = o.children.single()
        val before = fingerprint(f)
        client.send(HttpMethod.Put, "${f.path}/unions/${o.id}/children/${outKid.childId}", Actor.CONTRIBUTOR, encode(UpdateUnionChildRequest("ADOPTED", outKid.version)))
            .expectError(HttpStatusCode.Forbidden, outside(f), "relation in O")
        client.send(HttpMethod.Delete, "${f.path}/unions/${o.id}/children/${outKid.childId}?version=${outKid.version}", Actor.CONTRIBUTOR)
            .expectError(HttpStatusCode.Forbidden, outside(f), "unlink in O")
        client.send(HttpMethod.Post, "${f.path}/unions", Actor.CONTRIBUTOR, encode(CreateUnionRequest(f.id("out1"), f.id("loner"), "PARTNERS")))
            .expectError(HttpStatusCode.Forbidden, outside(f), "outsiders' union")
        client.send(HttpMethod.Put, "${f.path}/unions/${o.id}", Actor.CONTRIBUTOR, encode(UpdateUnionRequest(f.id("out1"), f.id("out2"), "DIVORCED", version = o.version)))
            .expectError(HttpStatusCode.Forbidden, outside(f), "outsiders' union update")
        client.send(HttpMethod.Delete, "${f.path}/unions/${o.id}?version=${o.version}", Actor.VIEWER)
            .expectError(HttpStatusCode.Forbidden, noEdit(f), "viewer")
        assertEquals(before, fingerprint(f))

        // One live end is enough: cousin (contributor grant) and a fresh person of theirs.
        val draft = client.send(HttpMethod.Post, "${f.path}/people", Actor.CONTRIBUTOR, encode(CreatePersonRequest(givenNames = "Novia", sex = "UNKNOWN", isLiving = false)))
            .decoded<CreatePersonResponse>(HttpStatusCode.Created).person
        val union = client.send(HttpMethod.Post, "${f.path}/unions", Actor.CONTRIBUTOR, encode(CreateUnionRequest(f.id("cousin"), draft.id, "PARTNERS")))
            .decoded<UnionDto>(HttpStatusCode.Created)
        // The branch owner of aunt's line can change it too, and remove the link they own.
        val p = client.unionsAsOwner(f).single { it.id == f.union("P") }
        val sib = p.children.single { it.childId == f.id("sib") }
        assertEquals(
            HttpStatusCode.OK,
            client.send(HttpMethod.Put, "${f.path}/unions/${p.id}/children/${sib.childId}", Actor.CONTRIBUTOR, encode(UpdateUnionChildRequest("ADOPTED", sib.version))).status,
        )
        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "${f.path}/unions/${union.id}?version=${union.version}", Actor.BRANCH_OWNER).status)
    }

    // --- Events, citations, photos: facts follow their subject -------------------------

    @Test
    fun `events and citations follow their subject, and a moved event needs both ends`() = api {
        val f = newFamily()
        val event = client.send(HttpMethod.Post, "${f.path}/events", Actor.CONTRIBUTOR, encode(CreateEventRequest(personId = f.id("cousin"), type = "BIRTH", date = "1990")))
            .decoded<EventDto>(HttpStatusCode.Created)
        val before = fingerprint(f)
        client.send(HttpMethod.Put, "${f.path}/events/${event.id}", Actor.CONTRIBUTOR, encode(UpdateEventRequest(personId = f.id("out1"), type = "BIRTH", version = event.version)))
            .expectError(HttpStatusCode.Forbidden, outside(f), "move onto out1")
        client.send(HttpMethod.Post, "${f.path}/events", Actor.CONTRIBUTOR, encode(CreateEventRequest(unionId = f.union("O"), type = "MARRIAGE")))
            .expectError(HttpStatusCode.Forbidden, outside(f), "outsiders' marriage")
        assertEquals(before, fingerprint(f))
        // An event the contributor can see (deceased gp1) but not edit is a 403...
        val gp1Event = client.send(HttpMethod.Post, "${f.path}/events", Actor.OWNER, encode(CreateEventRequest(personId = f.id("gp1"), type = "DEATH")))
            .decoded<EventDto>(HttpStatusCode.Created)
        val afterOwner = fingerprint(f)
        client.send(HttpMethod.Delete, "${f.path}/events/${gp1Event.id}?version=1", Actor.CONTRIBUTOR).expectError(HttpStatusCode.Forbidden, outside(f), "gp1 event")
        assertEquals(afterOwner, fingerprint(f))
        // ...and one they can't see at all (living outKid) reads as missing, like every read.
        val hidden = client.send(HttpMethod.Post, "${f.path}/events", Actor.OWNER, encode(CreateEventRequest(personId = f.id("outKid"), type = "BIRTH", notes = "secret")))
            .decoded<EventDto>(HttpStatusCode.Created)
        val missing = "No event with id ${hidden.id}"
        client.send(HttpMethod.Put, "${f.path}/events/${hidden.id}", Actor.CONTRIBUTOR, encode(UpdateEventRequest(personId = f.id("cousin"), type = "BIRTH", version = 1)))
            .expectError(HttpStatusCode.NotFound, missing, "hidden event update")
        client.send(HttpMethod.Delete, "${f.path}/events/${hidden.id}?version=1", Actor.CONTRIBUTOR).expectError(HttpStatusCode.NotFound, missing, "hidden event delete")
        val source = client.send(HttpMethod.Post, "${f.path}/sources", Actor.OWNER, encode(CreateSourceRequest(title = "Register", type = "CHURCH_RECORD")))
            .decoded<SourceDto>(HttpStatusCode.Created)
        val afterSource = fingerprint(f)
        fun cite(type: String, target: String, media: String? = null) =
            encode(CreateCitationRequest(sourceId = source.id, targetType = type, targetId = target, confidence = 2, mediaId = media))
        client.send(HttpMethod.Post, "${f.path}/citations", Actor.CONTRIBUTOR, cite("EVENT", hidden.id)).expectError(HttpStatusCode.NotFound, missing, "cite hidden event")
        client.send(HttpMethod.Post, "${f.path}/citations", Actor.CONTRIBUTOR, cite("PERSON", f.id("out1"))).expectError(HttpStatusCode.Forbidden, outside(f), "cite out1")
        client.send(HttpMethod.Post, "${f.path}/citations", Actor.CONTRIBUTOR, cite("UNION", f.union("O"))).expectError(HttpStatusCode.Forbidden, outside(f), "cite O")
        // Attaching media needs a tree-wide member, checked before the (here nonexistent) media.
        client.send(HttpMethod.Post, "${f.path}/citations", Actor.CONTRIBUTOR, cite("PERSON", f.id("cousin"), UUID.randomUUID().toString()))
            .expectError(HttpStatusCode.Forbidden, treeWide(f), "scan")
        val cousin = client.person(f, "cousin")
        client.send(HttpMethod.Put, "${f.path}/people/${cousin.id}/profile-photo", Actor.CONTRIBUTOR, encode(SetProfilePhotoRequest(UUID.randomUUID().toString(), cousin.version)))
            .expectError(HttpStatusCode.Forbidden, treeWide(f), "photo")
        assertEquals(afterSource, fingerprint(f))

        // Allowed: notes on their own event, a union event with one live partner, citations
        // on live people and on their event, removing a photo.
        assertEquals(
            HttpStatusCode.OK,
            client.send(HttpMethod.Put, "${f.path}/events/${event.id}", Actor.CONTRIBUTOR, encode(UpdateEventRequest(personId = f.id("cousin"), type = "BIRTH", notes = "ok", version = event.version))).status,
        )
        assertEquals(HttpStatusCode.Created, client.send(HttpMethod.Post, "${f.path}/events", Actor.CONTRIBUTOR, encode(CreateEventRequest(unionId = f.union("A"), type = "MARRIAGE"))).status)
        client.send(HttpMethod.Post, "${f.path}/citations", Actor.CONTRIBUTOR, cite("EVENT", event.id)).decoded<CitationDto>(HttpStatusCode.Created)
        client.send(HttpMethod.Post, "${f.path}/citations", Actor.CONTRIBUTOR, cite("UNION", f.union("A"))).decoded<CitationDto>(HttpStatusCode.Created)
        assertEquals(
            HttpStatusCode.OK,
            client.send(HttpMethod.Put, "${f.path}/people/${cousin.id}/profile-photo", Actor.CONTRIBUTOR, encode(SetProfilePhotoRequest(null, cousin.version))).status,
        )
    }

    // --- Tenant isolation ----------------------------------------------------------

    @Test
    fun `another tree's IDs are not found, and grants never reach into another tree`() = api {
        val f = newFamily()
        val other = TreeService.createTree(Actor.OTHER_OWNER.id, "Other family")
        val foreign = PersonService.createPerson(other.id, Actor.OTHER_OWNER.id, CreatePersonRequest(givenNames = "Foreign", sex = "UNKNOWN", isLiving = false))
            .first.id.value
        val before = fingerprint(f)
        client.send(
            HttpMethod.Put, "${f.path}/people/$foreign", Actor.CONTRIBUTOR,
            encode(UpdatePersonRequest(givenNames = "x", sex = "UNKNOWN", isLiving = false, version = 1)),
        ).expectError(HttpStatusCode.NotFound, "No person with id $foreign", "foreign person")
        client.send(HttpMethod.Post, "${f.path}/unions/${f.union("P")}/children", Actor.CONTRIBUTOR, encode(AddUnionChildRequest(foreign, "BIRTH")))
            .expectError(HttpStatusCode.NotFound, "No person with id $foreign", "foreign child")
        client.send(HttpMethod.Post, "${f.path}/unions", Actor.ADMIN, encode(CreateUnionRequest(f.id("me"), foreign, "PARTNERS")))
            .expectError(HttpStatusCode.NotFound, "No person with id $foreign", "foreign partner")
        assertEquals(before, fingerprint(f))
        client.send(
            HttpMethod.Put, "/api/v1/trees/${other.id.value}/people/$foreign", Actor.ADMIN,
            encode(UpdatePersonRequest(givenNames = "x", sex = "UNKNOWN", isLiving = false, version = 1)),
        ).expectError(HttpStatusCode.Forbidden, "Not a member of tree ${other.id.value}", "admin of another tree")
    }

    // --- Changing grants, graph and membership -----------------------------------------

    @Test
    fun `a revoked or narrowed grant is enforced on the next write`() = api {
        val f = newFamily()
        val contributorGrant = f.grants.getValue(Actor.CONTRIBUTOR)
        // Narrowed to the other family: cousin is out, outKid (no account) is in.
        GrantService.updateGrant(f.treeId, GrantId(contributorGrant), Actor.OWNER.id, UpdateGrantRequest("CONTRIBUTOR", f.id("out1"), "DESCENDANTS", version = 1))
        client.renamePerson(f, f.id("cousin"), Actor.CONTRIBUTOR, "no").expectError(HttpStatusCode.Forbidden, outside(f), "narrowed")
        assertEquals(HttpStatusCode.OK, client.renamePerson(f, f.id("outKid"), Actor.CONTRIBUTOR, "yes").status)
        // Revoked: no editing grant left, so not even the home branch.
        GrantService.deleteGrant(f.treeId, GrantId(contributorGrant), Actor.OWNER.id, version = 2)
        client.renamePerson(f, f.id("me"), Actor.CONTRIBUTOR, "no").expectError(HttpStatusCode.Forbidden, noEdit(f), "revoked")
    }

    /** Waits, at most 10 s, until another session waits on a lock. */
    private suspend fun awaitBlockedSession() = withTimeout(10_000) {
        val sql = """
            select count(*) from pg_stat_activity
            where pid <> pg_backend_pid() and datname = current_database() and wait_event_type = 'Lock'
        """.trimIndent()
        while (dataSource.query(sql)!!.toLong() == 0L) delay(25)
    }

    /**
     * Runs [change] in its own transaction and keeps it open while a contributor edit of
     * [label] starts and blocks on it; then commits and returns the edit's response.
     */
    private suspend fun ApplicationTestBuilder.editDuring(f: Family, label: String, change: (Connection) -> Unit): HttpResponse {
        val current = client.person(f, label)
        return raceWith(change) {
            client.send(
                HttpMethod.Put, "${f.path}/people/${current.id}", Actor.CONTRIBUTOR,
                encode(UpdatePersonRequest(givenNames = "raced", sex = "UNKNOWN", isLiving = current.isLiving, version = current.version)),
            )
        }
    }

    /**
     * Runs [change] in its own open READ COMMITTED transaction, starts [request], waits
     * until the request's transaction blocks on a lock, runs [whileBlocked] (autocommitted,
     * on another connection), then commits [change] and returns the request's response.
     */
    private suspend fun raceWith(
        change: (Connection) -> Unit,
        whileBlocked: (Connection) -> Unit = {},
        request: suspend () -> HttpResponse,
    ): HttpResponse = dataSource.connection.use { connection ->
        connection.autoCommit = false
        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        change(connection)
        coroutineScope {
            val pending = async { request() }
            awaitBlockedSession()
            dataSource.connection.use(whileBlocked)
            connection.commit()
            pending.await()
        }
    }

    private fun Connection.exec(sql: String, vararg args: Any) =
        prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.execute()
        }

    @Test
    fun `an edit waits for an in-flight grant delete and decides on what committed`() = api {
        val f = newFamily()
        val before = fingerprint(f)
        val response = editDuring(f, "cousin") { c ->
            c.exec("delete from tree_grant where id = ?", UUID.fromString(f.grants.getValue(Actor.CONTRIBUTOR)))
        }
        response.expectError(HttpStatusCode.Forbidden, noEdit(f), "grant deleted while the edit waited")
        assertEquals(before, fingerprint(f))
    }

    @Test
    fun `an edit waits for an in-flight graph change and decides on the new branches`() = api {
        val f = newFamily()
        val tree = UUID.fromString(f.tree)
        val key = tree.mostSignificantBits xor tree.leastSignificantBits
        val response = editDuring(f, "cousin") { c ->
            // A graph writer as the server runs one: the shared lock, the change, the generation.
            c.exec("select pg_advisory_xact_lock_shared(?)", key)
            c.exec("delete from union_child where tree_id = ? and child_id = ?", tree, UUID.fromString(f.id("cousin")))
            c.exec("update tree_graph_generation set generation = generation + 1 where tree_id = ?", tree)
        }
        // cousin left aunt's descendants before the edit could decide.
        response.expectError(HttpStatusCode.Forbidden, outside(f), "cousin unlinked while the edit waited")
        assertEquals("cousin", client.person(f, "cousin").givenNames)
    }

    @Test
    fun `an edit waits for an in-flight unlink of the member and loses the home branch`() = api {
        val f = newFamily()
        val response = editDuring(f, "sib") { c ->
            c.exec(
                "update membership set person_id = null, version = version + 1 where tree_id = ? and account_id = ?",
                UUID.fromString(f.tree), UUID.fromString(Actor.CONTRIBUTOR.id.value),
            )
        }
        response.expectError(HttpStatusCode.Forbidden, outside(f), "unlinked while the edit waited")
        assertEquals("sib", client.person(f, "sib").givenNames)
    }

    /**
     * The phantom the same-statement locking read prevents. The admin's only grant is
     * being downgraded (row locked, uncommitted); the admin's place write passes the gate
     * on the committed ADMIN role and its `FOR SHARE` read waits on that row. While it
     * waits, a new ADMIN grant for the same account commits. The waiting statement only
     * sees the downgraded row, so the write is refused; deciding on a second read would
     * have used the new, unlocked grant.
     */
    @Test
    fun `a grant committed while a write waits on its locked grants doesn't authorize that write`() = api {
        val f = newFamily()
        val tree = UUID.fromString(f.tree)
        val placesBefore = count("select count(*) from place where tree_id = ?", tree)
        val response = raceWith(
            change = { c ->
                c.exec("update tree_grant set role = 'VIEWER', version = version + 1 where id = ?", UUID.fromString(f.grants.getValue(Actor.ADMIN)))
            },
            whileBlocked = { c ->
                c.exec(
                    "insert into tree_grant (id, tree_id, account_id, role, anchor_person_id, direction, include_partners, created_by_account_id) " +
                        "values (gen_random_uuid(), ?, ?, 'ADMIN', ?, 'WHOLE_TREE', false, ?)",
                    tree, UUID.fromString(Actor.ADMIN.id.value), UUID.fromString(f.id("gp1")), UUID.fromString(Actor.OWNER.id.value),
                )
            },
        ) {
            client.send(HttpMethod.Post, "${f.path}/places", Actor.ADMIN, encode(CreatePlaceRequest("Lima")))
        }
        response.expectError(HttpStatusCode.Forbidden, noEdit(f), "decided on the downgraded grant only")
        assertEquals(placesBefore, count("select count(*) from place where tree_id = ?", tree))
        // The new grant counts from the next request on.
        assertEquals(HttpStatusCode.Created, client.send(HttpMethod.Post, "${f.path}/places", Actor.ADMIN, encode(CreatePlaceRequest("Lima"))).status)
    }

    // --- Linked members without an editing grant ---------------------------------------

    @Test
    fun `a linked member with no grant or only a viewer grant can't change their own home branch`() = api {
        val f = newFamily()
        // AUNT is linked to aunt (home: gp1, gp2, mum, aunt, me, sib, cousin, partners), with
        // no grant; VIEWER is linked to sib (home: mum, dad, me, sib) with only VIEWER.
        for ((actor, home) in listOf(Actor.AUNT to listOf("aunt", "cousin", "gp1", "mum"), Actor.VIEWER to listOf("sib", "me", "mum"))) {
            val before = fingerprint(f)
            for (label in home) {
                client.renamePerson(f, f.id(label), actor, "nope").expectError(HttpStatusCode.Forbidden, noEdit(f), "$actor rename $label")
            }
            val own = home.first()
            client.send(HttpMethod.Post, "${f.path}/people", actor, encode(CreatePersonRequest(givenNames = "Kid", sex = "UNKNOWN", isLiving = false)))
                .expectError(HttpStatusCode.Forbidden, noEdit(f), "$actor create")
            client.send(HttpMethod.Post, "${f.path}/events", actor, encode(CreateEventRequest(personId = f.id(own), type = "BAPTISM")))
                .expectError(HttpStatusCode.Forbidden, noEdit(f), "$actor event")
            client.send(HttpMethod.Post, "${f.path}/unions/${f.union("P")}/children", actor, encode(AddUnionChildRequest(f.id("loner"), "ADOPTED")))
                .expectError(HttpStatusCode.Forbidden, noEdit(f), "$actor child link")
            client.send(HttpMethod.Post, "${f.path}/unions", actor, encode(CreateUnionRequest(f.id(own), f.id("loner"), "PARTNERS")))
                .expectError(HttpStatusCode.Forbidden, noEdit(f), "$actor union")
            assertEquals(before, fingerprint(f), "$actor wrote nothing")
            assertEquals(0L, auditsBy(f, actor), "$actor has no audit entry")
        }
    }

    // --- Policy before parameters --------------------------------------------------

    @Test
    fun `an out-of-branch write is refused before its body values or version are validated`() = api {
        val f = newFamily()
        val o = client.unionsAsOwner(f).single { it.id == f.union("O") }
        val outKid = o.children.single()
        val gp1Event = client.send(HttpMethod.Post, "${f.path}/events", Actor.OWNER, encode(CreateEventRequest(personId = f.id("gp1"), type = "DEATH")))
            .decoded<EventDto>(HttpStatusCode.Created)
        val badPerson = encode(UpdatePersonRequest(givenNames = " ", sex = "ROBOT", isLiving = false, version = 1))
        val before = fingerprint(f)
        val refused = listOf(
            Triple(HttpMethod.Put, "${f.path}/people/${f.id("out1")}", badPerson),
            Triple(HttpMethod.Delete, "${f.path}/people/${f.id("out1")}?version=abc", null),
            Triple(HttpMethod.Put, "${f.path}/people/${f.id("out1")}/profile-photo", """{"mediaId":"not-a-uuid","version":1}"""),
            Triple(HttpMethod.Put, "${f.path}/unions/${o.id}", encode(UpdateUnionRequest(f.id("out1"), f.id("out2"), "ROBOT", startDate = "2000", endDate = "1990", version = 1))),
            Triple(HttpMethod.Delete, "${f.path}/unions/${o.id}?version=0", null),
            Triple(HttpMethod.Put, "${f.path}/unions/${o.id}/children/${outKid.childId}", encode(UpdateUnionChildRequest("ROBOT", outKid.version))),
            Triple(HttpMethod.Delete, "${f.path}/unions/${o.id}/children/${outKid.childId}", null),
            Triple(HttpMethod.Put, "${f.path}/events/${gp1Event.id}", encode(UpdateEventRequest(type = "ROBOT", version = 1))),
            Triple(HttpMethod.Delete, "${f.path}/events/${gp1Event.id}?version=-1", null),
        )
        for ((method, path, body) in refused) {
            client.send(method, path, Actor.CONTRIBUTOR, body).expectError(HttpStatusCode.Forbidden, outside(f), "$method $path")
        }
        assertEquals(before, fingerprint(f))
        // The owner, who may make the change, gets the validation errors as before.
        assertEquals(HttpStatusCode.BadRequest, client.send(HttpMethod.Put, refused[0].second, Actor.OWNER, badPerson).status)
        assertEquals(HttpStatusCode.BadRequest, client.send(HttpMethod.Delete, refused[1].second, Actor.OWNER).status)
        assertEquals(HttpStatusCode.BadRequest, client.send(HttpMethod.Put, refused[7].second, Actor.OWNER, refused[7].third).status)
        assertEquals(before, fingerprint(f))
    }

    // --- Access management: grants and invitations (behind the invitation gate) --------

    @Test
    fun `with the invitation gate off, grant, invitation and member routes don't exist`() = api(management = false) {
        val f = newFamily()
        for ((method, path) in listOf(
            HttpMethod.Get to "${f.path}/grants",
            HttpMethod.Post to "${f.path}/grants",
            HttpMethod.Delete to "${f.path}/grants/${f.grants.getValue(Actor.VIEWER)}?version=1",
            HttpMethod.Post to "${f.path}/invitations",
            HttpMethod.Get to "${f.path}/members",
        )) {
            for (actor in listOf(Actor.OWNER, Actor.ADMIN)) {
                assertEquals(HttpStatusCode.NotFound, client.send(method, path, actor, "{}").status, "$actor $method $path")
            }
        }
    }

    @Test
    fun `a whole-tree admin manages grants through the API, versioned, same-tree and audited`() = api(management = true) {
        val f = newFamily()
        val g = "${f.path}/grants"
        val created = client.send(HttpMethod.Post, g, Actor.ADMIN, encode(CreateGrantRequest(Actor.AUNT.id.value, "CONTRIBUTOR", f.id("aunt"), "DESCENDANTS")))
            .decoded<GrantDto>(HttpStatusCode.Created)
        // AUNT can now edit their home branch.
        assertEquals(HttpStatusCode.OK, client.renamePerson(f, f.id("gp1"), Actor.AUNT, "Abuelo").status)
        val updated = client.send(
            HttpMethod.Put, "$g/${created.id}", Actor.ADMIN,
            encode(UpdateGrantRequest("VIEWER", f.id("aunt"), "DESCENDANTS", version = created.version)),
        ).decoded<GrantDto>()
        assertEquals("VIEWER" to created.version + 1, updated.role to updated.version)
        client.renamePerson(f, f.id("gp1"), Actor.AUNT, "no").expectError(HttpStatusCode.Forbidden, noEdit(f), "downgraded")
        client.send(HttpMethod.Put, "$g/${created.id}", Actor.ADMIN, encode(UpdateGrantRequest("CONTRIBUTOR", f.id("aunt"), "SIDE", version = created.version)))
            .expectError(HttpStatusCode.Conflict, "Grant ${created.id} has been changed since you read it; reload it and try again", "stale")
        assertEquals(HttpStatusCode.NoContent, client.send(HttpMethod.Delete, "$g/${created.id}?version=${updated.version}", Actor.ADMIN).status)
        assertNull(client.send(HttpMethod.Get, g, Actor.ADMIN).decoded<List<GrantDto>>().singleOrNull { it.id == created.id })
        assertEquals(
            "3",
            dataSource.query(
                "select count(*) from audit_log where tree_id = ? and entity_type = 'Grant' and entity_id = ? and actor_account_id = ?",
                UUID.fromString(f.tree), created.id, UUID.fromString(Actor.ADMIN.id.value),
            ),
            "create, update and delete each audited as the admin",
        )

        // Validation and tenant isolation.
        client.send(HttpMethod.Post, g, Actor.ADMIN, encode(CreateGrantRequest(Actor.AUNT.id.value, "ADMIN", f.id("gp1"), "SIDE")))
            .expectError(HttpStatusCode.BadRequest, GrantRules.ADMIN_SCOPE_MESSAGE, "limited admin")
        val other = TreeService.createTree(Actor.OTHER_OWNER.id, "Other family")
        val foreignPerson = PersonService.createPerson(other.id, Actor.OTHER_OWNER.id, CreatePersonRequest(givenNames = "Foreign", sex = "UNKNOWN", isLiving = false))
            .first.id.value
        val foreignGrant = GrantService.createGrant(other.id, Actor.OTHER_OWNER.id, CreateGrantRequest(Actor.OTHER_OWNER.id.value, "VIEWER", foreignPerson, "DESCENDANTS"))
        val grantsBefore = grantCount(f)
        client.send(HttpMethod.Post, g, Actor.ADMIN, encode(CreateGrantRequest(Actor.OTHER_OWNER.id.value, "VIEWER", f.id("gp1"), "SIDE")))
            .expectError(HttpStatusCode.NotFound, "No member with account id ${Actor.OTHER_OWNER.id.value}", "account of another tree")
        client.send(HttpMethod.Post, g, Actor.ADMIN, encode(CreateGrantRequest(Actor.AUNT.id.value, "VIEWER", foreignPerson, "SIDE")))
            .expectError(HttpStatusCode.NotFound, "No person with id $foreignPerson", "anchor of another tree")
        client.send(HttpMethod.Put, "$g/${foreignGrant.id}", Actor.ADMIN, encode(UpdateGrantRequest("VIEWER", f.id("gp1"), "SIDE", version = 1)))
            .expectError(HttpStatusCode.NotFound, "No grant with id ${foreignGrant.id}", "grant of another tree")
        client.send(HttpMethod.Delete, "$g/${foreignGrant.id}?version=1", Actor.ADMIN)
            .expectError(HttpStatusCode.NotFound, "No grant with id ${foreignGrant.id}", "delete in another tree")
        assertEquals(grantsBefore, grantCount(f))
        assertEquals(1L, count("select count(*) from tree_grant where id = ?", UUID.fromString(foreignGrant.id)))
    }

    @Test
    fun `only the owner or a whole-tree admin reaches grant and invitation management, and linking stays the owner's`() = api(management = true) {
        val f = newFamily()
        val before = fingerprint(f)
        val grantsBefore = grantCount(f)
        val grant = encode(CreateGrantRequest(Actor.AUNT.id.value, "BRANCH_OWNER", f.id("gp1"), "SIDE"))
        for ((actor, message) in listOf(
            Actor.CONTRIBUTOR to managerOnly(f),
            Actor.BRANCH_OWNER to managerOnly(f),
            Actor.VIEWER to noEdit(f),
            Actor.LIMITED_ADMIN to noEdit(f),
            Actor.INVITEE to notMember(f),
            Actor.OTHER_OWNER to notMember(f),
        )) {
            for (body in listOf(grant, "{ not json")) {
                client.send(HttpMethod.Post, "${f.path}/grants", actor, body).expectError(HttpStatusCode.Forbidden, message, "$actor grant $body")
                client.send(HttpMethod.Post, "${f.path}/invitations", actor, body).expectError(HttpStatusCode.Forbidden, message, "$actor invite $body")
            }
            client.send(HttpMethod.Get, "${f.path}/grants", actor).expectError(HttpStatusCode.Forbidden, message, "$actor list grants")
            client.send(HttpMethod.Get, "${f.path}/members", actor).expectError(HttpStatusCode.Forbidden, message, "$actor members")
        }
        assertEquals(before, fingerprint(f))
        assertEquals(grantsBefore, grantCount(f))
        // An admin sees the members (no email) but can't link them: that stays the owner's.
        assertEquals(HttpStatusCode.OK, client.send(HttpMethod.Get, "${f.path}/members", Actor.ADMIN).status)
        client.send(HttpMethod.Put, "${f.path}/members/${Actor.BRANCH_OWNER.id.value}/person", Actor.ADMIN, """{"personId":"${f.id("loner")}","version":1}""")
            .expectError(HttpStatusCode.Forbidden, ownerOnly(f), "admin links")
    }

    @Test
    fun `an admin's invitation works while the admin keeps their role, and is void while it is revoked`() = api(management = true) {
        val f = newFamily()
        val proposal = CreateInvitationRequest(grants = listOf(InvitationGrantRequest("CONTRIBUTOR", f.id("out1"), "DESCENDANTS")))
        val first = client.send(HttpMethod.Post, "${f.path}/invitations", Actor.ADMIN, encode(proposal)).decoded<CreatedInvitationResponse>(HttpStatusCode.Created)
        val second = client.send(HttpMethod.Post, "${f.path}/invitations", Actor.ADMIN, encode(proposal)).decoded<CreatedInvitationResponse>(HttpStatusCode.Created)
        val redeemed = client.send(HttpMethod.Post, "${f.path}/invitations/redeem", Actor.NEWCOMER, encode(RedeemInvitationRequest(first.token)))
            .decoded<RedeemInvitationResponse>()
        val proposed = redeemed.grants.single()
        assertEquals("CONTRIBUTOR", proposed.role)
        assertEquals(Actor.ADMIN.id.value, dataSource.query("select created_by_account_id from tree_grant where id = ?", UUID.fromString(proposed.id)))

        // The owner downgrades the admin: the admin's pending invitation is void meanwhile.
        val adminGrant = GrantId(f.grants.getValue(Actor.ADMIN))
        GrantService.updateGrant(f.treeId, adminGrant, Actor.OWNER.id, UpdateGrantRequest("VIEWER", f.id("gp1"), "WHOLE_TREE", version = 1))
        val membershipsBefore = count("select count(*) from membership where tree_id = ?", UUID.fromString(f.tree))
        val pendingBefore = count("select count(*) from invitation where tree_id = ?", UUID.fromString(f.tree))
        client.send(HttpMethod.Post, "${f.path}/invitations/redeem", Actor.NEWCOMER2, encode(RedeemInvitationRequest(second.token)))
            .expectError(HttpStatusCode.NotFound, INVITATION_NOT_FOUND_MESSAGE, "inviter no longer an admin")
        assertEquals(membershipsBefore, count("select count(*) from membership where tree_id = ?", UUID.fromString(f.tree)))
        assertEquals(pendingBefore, count("select count(*) from invitation where tree_id = ?", UUID.fromString(f.tree)))
        client.send(HttpMethod.Get, "${f.path}/invitations", Actor.ADMIN).expectError(HttpStatusCode.Forbidden, noEdit(f), "demoted admin lists")

        // Restored: the invitation (still pending, not expired) is valid again.
        GrantService.updateGrant(f.treeId, adminGrant, Actor.OWNER.id, UpdateGrantRequest("ADMIN", f.id("gp1"), "WHOLE_TREE", version = 2))
        assertEquals(
            HttpStatusCode.OK,
            client.send(HttpMethod.Post, "${f.path}/invitations/redeem", Actor.NEWCOMER2, encode(RedeemInvitationRequest(second.token))).status,
        )
        // An admin revokes an invitation at its version.
        val third = client.send(HttpMethod.Post, "${f.path}/invitations", Actor.ADMIN, encode(CreateInvitationRequest()))
            .decoded<CreatedInvitationResponse>(HttpStatusCode.Created)
        assertEquals(
            HttpStatusCode.NoContent,
            client.send(HttpMethod.Delete, "${f.path}/invitations/${third.invitation.id}?version=${third.invitation.version}", Actor.ADMIN).status,
        )
    }

    @Test
    fun `two admins changing each other's grants at once both complete`() = runBlocking {
        val f = newFamily()
        val a = f.grants.getValue(Actor.ADMIN)
        val b = f.grants.getValue(Actor.ADMIN2)
        fun version(grant: String) = count("select version from tree_grant where id = ?", UUID.fromString(grant))
        repeat(5) { round ->
            val anchor = f.id(if (round % 2 == 0) "gp2" else "gp1")
            coroutineScope {
                val first = async {
                    GrantService.updateGrant(f.treeId, GrantId(b), Actor.ADMIN.id, UpdateGrantRequest("ADMIN", anchor, "WHOLE_TREE", version = version(b)))
                }
                val second = async {
                    GrantService.updateGrant(f.treeId, GrantId(a), Actor.ADMIN2.id, UpdateGrantRequest("ADMIN", anchor, "WHOLE_TREE", version = version(a)))
                }
                first.await()
                second.await()
            }
        }
        assertEquals(6L, version(a))
        assertEquals(6L, version(b))
    }
}
