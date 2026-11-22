package com.moxietech.family_tree.server.collaboration

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.ApproveProposalRequest
import com.moxietech.family_tree.api.ChangeProposalDto
import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.CreateGrantRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePlaceRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.DecideProposalRequest
import com.moxietech.family_tree.api.EventDto
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.api.SetProfilePhotoRequest
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.api.UpdateEventRequest
import com.moxietech.family_tree.api.UpdatePersonRequest
import com.moxietech.family_tree.api.UpdateUnionRequest
import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.proposals.ProposalFields
import com.moxietech.family_tree.server.config.ApiErrorResponse
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.config.OIDC_AUTH_NAME
import com.moxietech.family_tree.server.config.configureErrorMapping
import com.moxietech.family_tree.server.graph.EventService
import com.moxietech.family_tree.server.graph.PersonService
import com.moxietech.family_tree.server.graph.UnionService
import com.moxietech.family_tree.server.graph.eventRoutes
import com.moxietech.family_tree.server.graph.personRoutes
import com.moxietech.family_tree.server.graph.placeRoutes
import com.moxietech.family_tree.server.graph.unionRoutes
import com.moxietech.family_tree.server.identity.AccountRepository
import com.moxietech.family_tree.server.identity.IdentityTestApi.query
import com.moxietech.family_tree.server.identity.OidcIdentity
import com.moxietech.family_tree.server.permissions.GrantService
import com.moxietech.family_tree.server.permissions.WriteAccess
import com.moxietech.family_tree.server.sources.citationRoutes
import com.moxietech.family_tree.server.trees.MembershipRepository
import com.moxietech.family_tree.server.trees.TreeService
import com.moxietech.family_tree.server.trees.treeRoutes
import com.moxietech.family_tree.api.GrantDto
import com.moxietech.family_tree.api.TreeCapabilitiesDto
import com.moxietech.family_tree.domain.GrantId
import com.moxietech.family_tree.api.CreateCitationRequest
import com.moxietech.family_tree.api.CreateSourceRequest
import com.moxietech.family_tree.server.graph.PlaceService
import com.moxietech.family_tree.server.sources.SourceService
import com.moxietech.family_tree.domain.MembershipStatus
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
 * FT-014: change proposals over HTTP through the production routes against PostgreSQL 16.
 *
 * Every test builds its own tree (people marked * are living; the owner created everyone):
 *
 *   gp1 + gp2 (1940)                    ->  mum, aunt
 *   mum + dad* (1975)                   ->  me, sib
 *   aunt + uncle                        ->  cousin
 *   out1 + out2                         ->  outKid*          (another family)
 *   loner                                                    (no relationships)
 *
 * Members:
 * - CONTRIBUTOR, linked to `me` (home branch: mum, dad, me, sib), CONTRIBUTOR on aunt's
 *   descendants with partners (aunt, uncle, cousin). `aunt` has an account (AUNT), so
 *   editing her is outside the contributor's live reach.
 * - AUNT, linked to `aunt`, no grants (read-only).
 * - VIEWER, VIEWER over the whole tree.
 * - BRANCH_OWNER, BRANCH_OWNER on aunt's descendants with partners.
 * - WIDE_BRANCH_OWNER, BRANCH_OWNER over the whole tree (owns every person, but isn't the owner).
 * - ADMIN, ADMIN over the whole tree (edits live; not a reviewer by that role).
 * - OTHER_OWNER owns another tree.
 */
class ChangeProposalTest {

    private enum class Actor(val email: String) {
        OWNER("ft014-owner@example.com"),
        CONTRIBUTOR("ft014-contributor@example.com"),
        AUNT("ft014-aunt@example.com"),
        VIEWER("ft014-viewer@example.com"),
        BRANCH_OWNER("ft014-branch-owner@example.com"),
        WIDE_BRANCH_OWNER("ft014-wide-branch-owner@example.com"),
        ADMIN("ft014-admin@example.com"),
        OTHER_OWNER("ft014-other-owner@example.com"),
    }

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val TEST_SECRET = "change-proposal-test-only"
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
        val grants = linkedMapOf<Actor, GrantDto>()
        fun id(label: String) = people.getValue(label)
        fun union(label: String) = unions.getValue(label)
        val path get() = "/api/v1/trees/$tree"
        val treeId get() = TreeId(tree)
        val treeUuid get() = Uuid.parse(tree)
    }

    private suspend fun newFamily(): Family {
        val owner = Actor.OWNER.id
        val f = Family(TreeService.createTree(owner, "FT-014 family").id.value)
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

        fun member(actor: Actor, person: String? = null) =
            MembershipRepository.insert(f.treeUuid, actor.uuid, person?.let { Uuid.parse(f.id(it)) }, MembershipStatus.ACTIVE)
        suspend fun grant(actor: Actor, role: String, anchor: String, direction: String, partners: Boolean = false) {
            f.grants[actor] =
                GrantService.createGrant(f.treeId, owner, CreateGrantRequest(actor.id.value, role, f.id(anchor), direction, includePartners = partners))
        }
        member(Actor.CONTRIBUTOR, "me")
        grant(Actor.CONTRIBUTOR, "CONTRIBUTOR", "aunt", "DESCENDANTS", partners = true)
        member(Actor.AUNT, "aunt")
        member(Actor.VIEWER)
        grant(Actor.VIEWER, "VIEWER", "gp1", "WHOLE_TREE")
        member(Actor.BRANCH_OWNER)
        grant(Actor.BRANCH_OWNER, "BRANCH_OWNER", "aunt", "DESCENDANTS", partners = true)
        member(Actor.WIDE_BRANCH_OWNER)
        grant(Actor.WIDE_BRANCH_OWNER, "BRANCH_OWNER", "gp1", "WHOLE_TREE")
        member(Actor.ADMIN)
        grant(Actor.ADMIN, "ADMIN", "gp1", "WHOLE_TREE")
        return f
    }

    /** The production routes; [proposals] is the sharing gate (`INVITATIONS_ENABLED`). */
    private fun api(proposals: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
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
                val settings = ChangeProposalSettings(enabled = proposals)
                treeRoutes(sharingEnabled = proposals)
                personRoutes(settings)
                unionRoutes(settings)
                eventRoutes(settings)
                placeRoutes()
                citationRoutes(settings)
                changeProposalRoutes(settings)
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

    private fun count(sql: String, vararg args: Any?): Long = dataSource.query(sql, *args)!!.toLong()

    /** Everything a graph write can change in [f]'s tree (not the audit log or proposals). */
    private fun graph(f: Family): List<Long> {
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
            "select generation from tree_graph_generation where tree_id = ?",
        ).map { count(it, tree) }
    }

    private fun proposals(f: Family): Long = count("select count(*) from change_proposal where tree_id = ?", UUID.fromString(f.tree))

    private fun audits(f: Family, entity: String, actor: Actor? = null): Long = if (actor == null) {
        count("select count(*) from audit_log where tree_id = ? and entity_type = ?", UUID.fromString(f.tree), entity)
    } else {
        count(
            "select count(*) from audit_log where tree_id = ? and entity_type = ? and actor_account_id = ?",
            UUID.fromString(f.tree), entity, UUID.fromString(actor.id.value),
        )
    }

    private suspend fun HttpClient.person(f: Family, label: String): PersonDto =
        send(HttpMethod.Get, "${f.path}/people/${f.id(label)}", Actor.OWNER).decoded()

    private suspend fun HttpClient.proposeRename(f: Family, label: String, actor: Actor, name: String, bio: String? = null): HttpResponse {
        val current = person(f, label)
        return send(
            HttpMethod.Put,
            "${f.path}/people/${f.id(label)}",
            actor,
            encode(UpdatePersonRequest(givenNames = name, sex = "UNKNOWN", isLiving = current.isLiving, bio = bio, version = current.version)),
        )
    }

    private suspend fun HttpClient.proposal(f: Family, id: String, actor: Actor): HttpResponse =
        send(HttpMethod.Get, "${f.path}/proposals/$id", actor)

    private suspend fun HttpClient.list(f: Family, actor: Actor): List<ChangeProposalDto> =
        send(HttpMethod.Get, "${f.path}/proposals", actor).decoded()

    private suspend fun HttpClient.approve(f: Family, p: ChangeProposalDto, actor: Actor, fields: List<String>? = null): HttpResponse =
        send(HttpMethod.Post, "${f.path}/proposals/${p.id}/approve", actor, encode(ApproveProposalRequest(p.version, fields)))

    private fun notFound(id: String) = ChangeProposalService.notFoundMessage(id)

    // --- The setting -------------------------------------------------------------------

    @Test
    fun `with proposals off, out-of-branch writes keep the FT-012 refusal and the routes don't exist`() = api(proposals = false) {
        val f = newFamily()
        val before = graph(f)
        client.proposeRename(f, "aunt", Actor.CONTRIBUTOR, "Tía").expectError(HttpStatusCode.Forbidden, WriteAccess.outsideBranchMessage(f.tree), "rename")
        assertEquals(before, graph(f))
        assertEquals(0L, proposals(f))
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "${f.path}/proposals", Actor.OWNER).status)
    }

    // --- Person update: queued, redacted, approved per field ---------------------------

    @Test
    fun `an out-of-branch person update is queued without a graph write and approved per field by the owner`() = api {
        val f = newFamily()
        val before = graph(f)
        val auditsBefore = audits(f, "Person")
        val queued = client.proposeRename(f, "aunt", Actor.CONTRIBUTOR, "Tía", bio = "Teacher").decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        assertEquals("PENDING", queued.status)
        assertEquals("UPDATE_PERSON", queued.operation)
        assertEquals("OUTSIDE_BRANCH", queued.reason)
        assertEquals(f.id("aunt"), queued.targetId)
        assertTrue(queued.canWithdraw)
        assertEquals(JsonPrimitive("Tía"), queued.fields.single { it.field == "GIVEN_NAMES" }.proposed["givenNames"])
        // Pending work is not a graph write: no row, version, generation or Person audit changed.
        assertEquals(before, graph(f))
        assertEquals(auditsBefore, audits(f, "Person"))
        assertEquals(1L, audits(f, CHANGE_PROPOSAL_ENTITY, Actor.CONTRIBUTOR))
        assertEquals("aunt", client.person(f, "aunt").givenNames)

        // Visible to the proposer and to reviewers of every affected person, nobody else.
        assertEquals(listOf(queued.id), client.list(f, Actor.CONTRIBUTOR).map { it.id })
        val forBranchOwner = client.list(f, Actor.BRANCH_OWNER).single()
        assertTrue(forBranchOwner.canReview)
        assertEquals(JsonPrimitive("aunt"), forBranchOwner.fields.single { it.field == "GIVEN_NAMES" }.current!!.jsonObject["givenNames"])
        assertEquals(true, forBranchOwner.fields.single { it.field == "GIVEN_NAMES" }.changed)
        assertEquals(false, forBranchOwner.fields.single { it.field == "SEX" }.changed)
        assertEquals(false, forBranchOwner.stale)
        for (actor in listOf(Actor.VIEWER, Actor.AUNT, Actor.ADMIN)) {
            assertEquals(emptyList(), client.list(f, actor), "$actor")
            client.proposal(f, queued.id, actor).expectError(HttpStatusCode.NotFound, notFound(queued.id), "$actor")
        }
        // A contributor can't review, even their own; admins aren't reviewers by that role.
        for (actor in listOf(Actor.CONTRIBUTOR, Actor.VIEWER, Actor.ADMIN)) {
            client.approve(f, queued, actor).expectError(HttpStatusCode.Forbidden, WriteAccess.notReviewerMessage(f.tree), "$actor approve")
        }
        assertEquals(before, graph(f))

        // Unknown field names are a 400 with nothing written.
        client.approve(f, queued, Actor.OWNER, listOf("PARTNERS"))
            .expectError(HttpStatusCode.BadRequest, ProposalFields.unknownFieldMessage("PARTNERS"), "wrong field")
        assertEquals(before, graph(f))

        val ownerPersonAudits = audits(f, "Person", Actor.OWNER)
        val applied = client.approve(f, queued, Actor.OWNER, listOf("GIVEN_NAMES")).decoded<ChangeProposalDto>()
        assertEquals("APPLIED", applied.status)
        assertEquals(listOf("GIVEN_NAMES"), applied.approvedFields)
        assertEquals(Actor.OWNER.id.value, applied.reviewerAccountId)
        val aunt = client.person(f, "aunt")
        assertEquals("Tía", aunt.givenNames)
        assertNull(aunt.bio, "the bio wasn't approved")
        // The graph change is audited as the reviewer's, once; the transition too.
        assertEquals(ownerPersonAudits + 1, audits(f, "Person", Actor.OWNER))
        assertEquals(1L, audits(f, CHANGE_PROPOSAL_ENTITY, Actor.OWNER))

        // A retry can't apply it twice.
        val version = aunt.version
        client.approve(f, queued.copy(version = applied.version), Actor.OWNER)
            .expectError(HttpStatusCode.Conflict, ChangeProposalService.alreadyDecidedMessage(queued.id), "retry")
        assertEquals(version, client.person(f, "aunt").version)
    }

    @Test
    fun `a branch owner approves inside their branch, and the proposer withdraws or the reviewer rejects`() = api {
        val f = newFamily()
        val first = client.proposeRename(f, "aunt", Actor.CONTRIBUTOR, "Tía").decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        client.approve(f, first, Actor.BRANCH_OWNER).decoded<ChangeProposalDto>()
        assertEquals("Tía", client.person(f, "aunt").givenNames)
        assertEquals(1L, audits(f, "Person", Actor.BRANCH_OWNER))

        val second = client.proposeRename(f, "aunt", Actor.CONTRIBUTOR, "Tía Ana").decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        // Someone else's proposal: a branch owner can't withdraw it (they can't tell it apart),
        // and the owner is told only the proposer can.
        client.send(HttpMethod.Post, "${f.path}/proposals/${second.id}/withdraw", Actor.BRANCH_OWNER, encode(DecideProposalRequest(second.version)))
            .expectError(HttpStatusCode.NotFound, notFound(second.id), "branch owner withdraw")
        client.send(HttpMethod.Post, "${f.path}/proposals/${second.id}/withdraw", Actor.OWNER, encode(DecideProposalRequest(second.version)))
            .expectError(HttpStatusCode.Forbidden, ChangeProposalService.PROPOSER_ONLY_MESSAGE, "owner withdraw")
        val withdrawn = client.send(HttpMethod.Post, "${f.path}/proposals/${second.id}/withdraw", Actor.CONTRIBUTOR, encode(DecideProposalRequest(second.version)))
            .decoded<ChangeProposalDto>()
        assertEquals("WITHDRAWN", withdrawn.status)
        client.approve(f, withdrawn, Actor.OWNER).expectError(HttpStatusCode.Conflict, ChangeProposalService.alreadyDecidedMessage(second.id), "approve withdrawn")
        assertEquals("Tía", client.person(f, "aunt").givenNames)

        val third = client.proposeRename(f, "aunt", Actor.CONTRIBUTOR, "Nope").decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        val before = graph(f)
        val rejected = client.send(HttpMethod.Post, "${f.path}/proposals/${third.id}/reject", Actor.BRANCH_OWNER, encode(DecideProposalRequest(third.version)))
            .decoded<ChangeProposalDto>()
        assertEquals("REJECTED", rejected.status)
        assertEquals(before, graph(f))
        assertEquals("REJECTED", client.proposal(f, third.id, Actor.CONTRIBUTOR).decoded<ChangeProposalDto>().status)
    }

    @Test
    fun `a stale proposal is surfaced and refused, and stays pending`() = api {
        val f = newFamily()
        val queued = client.proposeRename(f, "aunt", Actor.CONTRIBUTOR, "Tía").decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        // The owner edits aunt directly meanwhile.
        client.proposeRename(f, "aunt", Actor.OWNER, "Ana").decoded<PersonDto>()
        assertEquals(true, client.proposal(f, queued.id, Actor.OWNER).decoded<ChangeProposalDto>().stale)
        val before = graph(f)
        assertEquals(HttpStatusCode.Conflict, client.approve(f, queued, Actor.OWNER).status)
        assertEquals(before, graph(f))
        assertEquals(0L, audits(f, CHANGE_PROPOSAL_ENTITY, Actor.OWNER))
        assertEquals("PENDING", client.proposal(f, queued.id, Actor.OWNER).decoded<ChangeProposalDto>().status)
        assertEquals("Ana", client.person(f, "aunt").givenNames)
    }

    // --- Review scope ------------------------------------------------------------------

    @Test
    fun `a cross-branch change needs a reviewer who owns every affected person`() = api {
        val f = newFamily()
        // gp1 is outside both the contributor's reach and the aunt-branch owner's.
        val gp1 = client.proposeRename(f, "gp1", Actor.CONTRIBUTOR, "Abuelo").decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        assertEquals(emptyList(), client.list(f, Actor.BRANCH_OWNER))
        client.approve(f, gp1, Actor.BRANCH_OWNER).expectError(HttpStatusCode.NotFound, notFound(gp1.id), "branch owner, outside")
        // A child link between the aunt's union (inside the branch) and loner (outside):
        // the contributor edits uncle live, but it would pull loner into their reach.
        val adopt = client.send(
            HttpMethod.Post, "${f.path}/unions/${f.union("A")}/children", Actor.CONTRIBUTOR, encode(AddUnionChildRequest(f.id("loner"), "ADOPTED")),
        ).decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        assertEquals("WIDENS_REACH", adopt.reason)
        assertEquals(0L, count("select count(*) from union_child where tree_id = ? and child_id = ?", UUID.fromString(f.tree), UUID.fromString(f.id("loner"))))
        client.approve(f, adopt, Actor.BRANCH_OWNER).expectError(HttpStatusCode.NotFound, notFound(adopt.id), "branch owner, cross-branch")
        // The whole-tree branch owner owns everyone, but approving would widen the proposer's
        // reach: only the tree owner grants that.
        val before = graph(f)
        client.approve(f, adopt, Actor.WIDE_BRANCH_OWNER).expectError(HttpStatusCode.Forbidden, WriteAccess.reviewWidensProposerMessage(f.tree), "wide branch owner")
        assertEquals(before, graph(f))
        assertEquals("PENDING", client.proposal(f, adopt.id, Actor.OWNER).decoded<ChangeProposalDto>().status)
        // A plain out-of-branch edit is theirs to approve.
        client.approve(f, gp1, Actor.WIDE_BRANCH_OWNER).decoded<ChangeProposalDto>()
        assertEquals("Abuelo", client.person(f, "gp1").givenNames)
        // The tree owner approves the widening one, audited as theirs.
        val ownerLinkAudits = audits(f, "UnionChild", Actor.OWNER)
        client.approve(f, adopt, Actor.OWNER).decoded<ChangeProposalDto>()
        assertEquals(1L, count("select count(*) from union_child where tree_id = ? and child_id = ?", UUID.fromString(f.tree), UUID.fromString(f.id("loner"))))
        assertEquals(ownerLinkAudits + 1, audits(f, "UnionChild", Actor.OWNER))
    }

    @Test
    fun `concurrent approvals apply a proposal once`() = api {
        val f = newFamily()
        val queued = client.proposeRename(f, "gp1", Actor.CONTRIBUTOR, "Abuelo").decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        val versionBefore = client.person(f, "gp1").version
        val statuses = coroutineScope {
            listOf(Actor.OWNER, Actor.WIDE_BRANCH_OWNER).map { actor -> async { client.approve(f, queued, actor).status } }.map { it.await() }
        }
        assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses.toSet(), "one approval wins, the other sees it decided")
        assertEquals(versionBefore + 1, client.person(f, "gp1").version)
        assertEquals(1L, count("select count(*) from audit_log where tree_id = ? and entity_type = 'Person' and action = 'UPDATE'", UUID.fromString(f.tree)))
    }

    // --- Privacy -----------------------------------------------------------------------

    @Test
    fun `hidden events stay missing and proposers never see values they can't read`() = api {
        val f = newFamily()
        val owner = Actor.OWNER.id
        val event = EventService.createEvent(f.treeId, owner, CreateEventRequest(personId = f.id("outKid"), type = "BIRTH", date = "2001", notes = "secret"))
        val before = graph(f)
        // outKid is living and hidden from the contributor: their event reads as missing,
        // exactly like a random ID, and is never queued.
        val random = UUID.randomUUID().toString()
        for (id in listOf(event.id, random)) {
            client.send(
                HttpMethod.Put, "${f.path}/events/$id", Actor.CONTRIBUTOR,
                encode(UpdateEventRequest(personId = f.id("outKid"), type = "BIRTH", version = 1)),
            ).expectError(HttpStatusCode.NotFound, "No event with id $id", "update $id")
            client.send(HttpMethod.Delete, "${f.path}/events/$id?version=1", Actor.CONTRIBUTOR)
                .expectError(HttpStatusCode.NotFound, "No event with id $id", "delete $id")
        }
        assertEquals(0L, proposals(f))
        // Marking a hidden living person deceased is a proposal for review, and the proposer
        // sees only their own values: no stored values, no "changed", no staleness.
        val marked = client.send(
            HttpMethod.Put, "${f.path}/people/${f.id("outKid")}", Actor.CONTRIBUTOR,
            encode(UpdatePersonRequest(givenNames = "guess", sex = "UNKNOWN", isLiving = false, version = 1)),
        ).decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        assertEquals("REVEALS_PRIVATE", marked.reason)
        val mine = client.proposal(f, marked.id, Actor.CONTRIBUTOR).decoded<ChangeProposalDto>()
        assertTrue(mine.fields.all { it.current == null && it.changed == null })
        assertNull(mine.stale)
        assertFalse(client.send(HttpMethod.Get, "${f.path}/proposals", Actor.CONTRIBUTOR).bodyAsText().contains("outKid"))
        // The owner sees what is stored, and which fields differ.
        val owners = client.proposal(f, marked.id, Actor.OWNER).decoded<ChangeProposalDto>()
        assertEquals(true, owners.fields.single { it.field == "GIVEN_NAMES" }.changed)
        assertEquals(false, owners.stale)
        assertEquals(before, graph(f))
        // An event on a person outside the contributor's reach, but visible (deceased), is queued.
        val created = client.send(
            HttpMethod.Post, "${f.path}/events", Actor.CONTRIBUTOR, encode(CreateEventRequest(personId = f.id("gp1"), type = "BIRTH", date = "1915")),
        ).decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        assertEquals(before, graph(f))
        val ownerEventAudits = audits(f, "Event", Actor.OWNER)
        client.approve(f, created, Actor.OWNER).decoded<ChangeProposalDto>()
        val gp1Events: List<EventDto> = client.send(HttpMethod.Get, "${f.path}/people/${f.id("gp1")}/events", Actor.OWNER).decoded()
        assertEquals(listOf("1915"), gp1Events.map { it.date?.displayText })
        assertEquals(ownerEventAudits + 1, audits(f, "Event", Actor.OWNER), "the event create is audited as the reviewer's")
        assertEquals(0L, audits(f, "Event", Actor.CONTRIBUTOR))
    }

    // --- What stays refused ------------------------------------------------------------

    @Test
    fun `viewers, members without grants, tree-wide and owner-only operations are still refused, never queued`() = api {
        val f = newFamily()
        val before = graph(f)
        client.proposeRename(f, "gp1", Actor.VIEWER, "x").expectError(HttpStatusCode.Forbidden, TreeService.noEditAccessMessage(f.tree), "viewer")
        client.proposeRename(f, "cousin", Actor.AUNT, "x").expectError(HttpStatusCode.Forbidden, TreeService.noEditAccessMessage(f.tree), "aunt")
        client.proposeRename(f, "gp1", Actor.OTHER_OWNER, "x").expectError(HttpStatusCode.Forbidden, "Not a member of tree ${f.tree}", "other tree")
        client.send(HttpMethod.Post, "${f.path}/places", Actor.CONTRIBUTOR, encode(CreatePlaceRequest("Lima")))
            .expectError(HttpStatusCode.Forbidden, TreeService.treeWideOnlyMessage(f.tree), "place")
        val aunt = client.person(f, "aunt")
        client.send(HttpMethod.Put, "${f.path}/people/${f.id("aunt")}/profile-photo", Actor.CONTRIBUTOR, encode(SetProfilePhotoRequest(null, aunt.version)))
            .expectError(HttpStatusCode.Forbidden, WriteAccess.outsideBranchMessage(f.tree), "photo")
        // Malformed bodies from someone who may propose are the usual 400, nothing queued.
        val gp1 = client.person(f, "gp1")
        assertEquals(
            HttpStatusCode.BadRequest,
            client.send(
                HttpMethod.Put, "${f.path}/people/${f.id("gp1")}", Actor.CONTRIBUTOR,
                encode(UpdatePersonRequest(givenNames = "x", sex = "NOPE", isLiving = false, version = gp1.version)),
            ).status,
        )
        // A stale version is the usual 409, nothing queued.
        assertEquals(
            HttpStatusCode.Conflict,
            client.send(
                HttpMethod.Put, "${f.path}/people/${f.id("gp1")}", Actor.CONTRIBUTOR,
                encode(UpdatePersonRequest(givenNames = "x", sex = "UNKNOWN", isLiving = false, version = gp1.version + 5)),
            ).status,
        )
        // Another tree's IDs are the generic 404.
        val other = TreeService.createTree(Actor.OTHER_OWNER.id, "Other")
        val stranger = PersonService.createPerson(other.id, Actor.OTHER_OWNER.id, CreatePersonRequest("Stranger", sex = "UNKNOWN", isLiving = false)).first
        assertEquals(
            HttpStatusCode.NotFound,
            client.send(
                HttpMethod.Put, "${f.path}/people/${stranger.id.value}", Actor.CONTRIBUTOR,
                encode(UpdatePersonRequest(givenNames = "x", sex = "UNKNOWN", isLiving = false, version = 1)),
            ).status,
        )
        assertEquals(before, graph(f))
        assertEquals(0L, proposals(f))
        // Proposal routes are per tree: the other tree's owner can't read this tree's.
        val queued = client.proposeRename(f, "gp1", Actor.CONTRIBUTOR, "Abuelo").decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        client.proposal(f, queued.id, Actor.OTHER_OWNER).expectError(HttpStatusCode.Forbidden, "Not a member of tree ${f.tree}", "other owner")
        client.send(HttpMethod.Get, "/api/v1/trees/${other.id.value}/proposals/${queued.id}", Actor.OTHER_OWNER)
            .expectError(HttpStatusCode.NotFound, notFound(queued.id), "other tree's URL")
    }

    // --- Relationships: grouped fields ---------------------------------------------------

    @Test
    fun `a union update keeps partners and dates grouped, and only approved groups apply`() = api {
        val f = newFamily()
        val unions: List<UnionDto> = client.send(HttpMethod.Get, "${f.path}/unions", Actor.OWNER).decoded()
        val g = unions.single { it.id == f.union("G") }
        val before = graph(f)
        val queued = client.send(
            HttpMethod.Put, "${f.path}/unions/${g.id}", Actor.CONTRIBUTOR,
            encode(UpdateUnionRequest(f.id("gp1"), f.id("gp2"), "DIVORCED", startDate = "1941", endDate = "1960", version = g.version)),
        ).decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        assertEquals(listOf("PARTNERS", "UNION_TYPE", "UNION_DATES"), queued.fields.map { it.field })
        assertEquals(setOf("startDate", "endDate"), queued.fields.single { it.field == "UNION_DATES" }.proposed.keys)
        assertEquals(before, graph(f))
        client.approve(f, queued, Actor.OWNER, listOf("UNION_TYPE")).decoded<ChangeProposalDto>()
        val after = client.send(HttpMethod.Get, "${f.path}/unions", Actor.OWNER).decoded<List<UnionDto>>().single { it.id == g.id }
        assertEquals("DIVORCED", after.type)
        assertEquals("1940", after.startDate?.displayText)
        assertNull(after.endDate)
        assertEquals(g.partner1Id, after.partner1Id)
    }

    // --- Only the tree owner approves an expansion of the proposer's access -------------
    // (user decision 2026-11-22; the graph-reach case is in the cross-branch test above)

    @Test
    fun `a branch owner can't approve marking a person hidden from the proposer deceased, unless they already see them`() = api {
        val f = newFamily()
        val marked = client.send(
            HttpMethod.Put, "${f.path}/people/${f.id("outKid")}", Actor.CONTRIBUTOR,
            encode(UpdatePersonRequest(givenNames = "outKid", sex = "UNKNOWN", isLiving = false, version = 1)),
        ).decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        val before = graph(f)
        client.approve(f, marked, Actor.WIDE_BRANCH_OWNER)
            .expectError(HttpStatusCode.Forbidden, WriteAccess.reviewWidensProposerMessage(f.tree), "would reveal outKid to the proposer")
        assertEquals(before, graph(f))
        assertEquals("PENDING", client.proposal(f, marked.id, Actor.OWNER).decoded<ChangeProposalDto>().status)
        // Scoped to what is actually newly exposed: once a grant shows the proposer outKid,
        // marking them deceased reveals nothing new to the proposer.
        GrantService.createGrant(f.treeId, Actor.OWNER.id, CreateGrantRequest(Actor.CONTRIBUTOR.id.value, "VIEWER", f.id("out1"), "DESCENDANTS"))
        client.approve(f, marked, Actor.WIDE_BRANCH_OWNER).decoded<ChangeProposalDto>()
        assertEquals(false, client.person(f, "outKid").isLiving)
    }

    @Test
    fun `a branch owner can't approve moving facts off a person hidden from the proposer onto people they see`() = api {
        val f = newFamily()
        val p = client.send(HttpMethod.Get, "${f.path}/unions", Actor.OWNER).decoded<List<UnionDto>>().single { it.id == f.union("P") }
        // Dropping dad (living, hidden from the contributor) from P: its dates and citations
        // would become readable through mum alone.
        val drop = client.send(
            HttpMethod.Put, "${f.path}/unions/${p.id}", Actor.CONTRIBUTOR,
            encode(UpdateUnionRequest(f.id("mum"), null, "MARRIED", startDate = "1975", version = p.version)),
        ).decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        assertEquals("REVEALS_PRIVATE", drop.reason)
        // dad's event (the contributor edits dad live: home branch) moved to gp1, whom they see.
        val event = EventService.createEvent(f.treeId, Actor.OWNER.id, CreateEventRequest(personId = f.id("dad"), type = "BIRTH", date = "1950"))
        val toGp1 = client.send(
            HttpMethod.Put, "${f.path}/events/${event.id}", Actor.CONTRIBUTOR,
            encode(UpdateEventRequest(personId = f.id("gp1"), type = "BIRTH", date = "1950", version = event.version)),
        ).decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        val toOutKid = client.send(
            HttpMethod.Put, "${f.path}/events/${event.id}", Actor.CONTRIBUTOR,
            encode(UpdateEventRequest(personId = f.id("outKid"), type = "BIRTH", date = "1950", version = event.version)),
        ).decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        val before = graph(f)
        for (proposal in listOf(drop, toGp1)) {
            client.approve(f, proposal, Actor.WIDE_BRANCH_OWNER)
                .expectError(HttpStatusCode.Forbidden, WriteAccess.reviewWidensProposerMessage(f.tree), proposal.operation)
        }
        assertEquals(before, graph(f))
        // Onto outKid, also hidden from the proposer: nothing becomes readable to them.
        client.approve(f, toOutKid, Actor.WIDE_BRANCH_OWNER).decoded<ChangeProposalDto>()
        val outKidEvents: List<EventDto> = client.send(HttpMethod.Get, "${f.path}/people/${f.id("outKid")}/events", Actor.OWNER).decoded()
        assertEquals(listOf(event.id), outKidEvents.map { it.id })
        // The tree owner may approve the expansion.
        client.approve(f, drop, Actor.OWNER).decoded<ChangeProposalDto>()
        assertNull(client.send(HttpMethod.Get, "${f.path}/unions", Actor.OWNER).decoded<List<UnionDto>>().single { it.id == p.id }.partner2Id)
    }

    // --- Hidden dependencies (review fix) ----------------------------------------------

    @Test
    fun `a delete outside the branch never reveals a hidden dependency`() = api {
        val f = newFamily()
        val owner = Actor.OWNER.id
        val secret = PersonService.createPerson(f.treeId, owner, CreatePersonRequest("Secret", sex = "UNKNOWN", isLiving = true)).first
        EventService.createEvent(f.treeId, owner, CreateEventRequest(personId = secret.id.value, type = "BIRTH", date = "1990"))
        val bare = PersonService.createPerson(f.treeId, owner, CreatePersonRequest("Bare", sex = "UNKNOWN", isLiving = true)).first
        val before = graph(f)
        // A person whose only reference is an event hidden from the contributor answers
        // exactly like an unreferenced one: queued, never "still referenced".
        val responses = listOf(secret, bare).map { person ->
            client.send(HttpMethod.Delete, "${f.path}/people/${person.id.value}?version=1", Actor.CONTRIBUTOR)
                .decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        }
        assertEquals(listOf("DELETE_PERSON", "DELETE_PERSON"), responses.map { it.operation })
        assertEquals(responses[0].fields, responses[1].fields)
        assertEquals(before, graph(f))
        // The authorized path still refuses a referenced delete, for the reviewer and live.
        val queued = client.list(f, Actor.OWNER).single { it.targetId == secret.id.value }
        assertEquals(HttpStatusCode.Conflict, client.approve(f, queued, Actor.OWNER).status)
        assertEquals(before, graph(f))
        assertEquals("PENDING", client.proposal(f, queued.id, Actor.OWNER).decoded<ChangeProposalDto>().status)
        assertEquals(HttpStatusCode.Conflict, client.send(HttpMethod.Delete, "${f.path}/people/${secret.id.value}?version=1", Actor.OWNER).status)
        // The unreferenced one applies.
        client.approve(f, client.list(f, Actor.OWNER).single { it.targetId == bare.id.value }, Actor.OWNER).decoded<ChangeProposalDto>()
        assertEquals(HttpStatusCode.NotFound, client.send(HttpMethod.Get, "${f.path}/people/${bare.id.value}", Actor.OWNER).status)
    }

    // --- Read context for reviewers (review fix) ----------------------------------------

    @Test
    fun `proposals name the places, sources and events they use, and never a hidden event`() = api {
        val f = newFamily()
        val owner = Actor.OWNER.id
        val lima = PlaceService.createPlace(f.treeId, owner, CreatePlaceRequest("Lima", hierarchy = "Peru"))
        val cusco = PlaceService.createPlace(f.treeId, owner, CreatePlaceRequest("Cusco", hierarchy = "Peru"))
        val census = SourceService.createSource(f.treeId, owner, CreateSourceRequest(title = "Census 1940", type = "CENSUS"))
        SourceService.createSource(f.treeId, owner, CreateSourceRequest(title = "Parish book", type = "CHURCH_RECORD"))
        val birth = EventService.createEvent(f.treeId, owner, CreateEventRequest(personId = f.id("gp1"), type = "BIRTH", date = "1915", placeId = cusco.id))
        // gp1 is deceased (visible to every member) and outside the contributor's reach.
        val moved = client.send(
            HttpMethod.Put, "${f.path}/events/${birth.id}", Actor.CONTRIBUTOR,
            encode(UpdateEventRequest(personId = f.id("gp1"), type = "BIRTH", date = "1915", placeId = lima.id, version = birth.version)),
        ).decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        val cited = client.send(
            HttpMethod.Post, "${f.path}/citations", Actor.CONTRIBUTOR,
            encode(CreateCitationRequest(sourceId = census.id, targetType = "EVENT", targetId = birth.id, confidence = 2)),
        ).decoded<ChangeProposalDto>(HttpStatusCode.Accepted)

        for (actor in listOf(Actor.OWNER, Actor.CONTRIBUTOR)) {
            val shown = client.proposal(f, moved.id, actor).decoded<ChangeProposalDto>().references.associateBy { it.id }
            // The event being edited, the place proposed and the place stored now: each distinct.
            assertEquals(setOf(birth.id, lima.id, cusco.id), shown.keys, "$actor")
            assertEquals("EVENT", shown.getValue(birth.id).kind)
            assertEquals("BIRTH", shown.getValue(birth.id).eventType)
            assertEquals("1915", shown.getValue(birth.id).eventDate)
            assertEquals(f.id("gp1"), shown.getValue(birth.id).personId)
            assertEquals("Lima" to "Peru", shown.getValue(lima.id).let { it.name to it.detail })
            assertEquals("Cusco", shown.getValue(cusco.id).name)
            val citation = client.proposal(f, cited.id, actor).decoded<ChangeProposalDto>().references.associateBy { it.id }
            assertEquals(setOf(census.id, birth.id), citation.keys, "$actor: only the source and event it names")
            assertEquals("SOURCE" to "Census 1940", citation.getValue(census.id).let { it.kind to it.name })
        }

        // An event the contributor may see but not edit, which is later hidden from them.
        val viewer = GrantService.createGrant(f.treeId, owner, CreateGrantRequest(Actor.CONTRIBUTOR.id.value, "VIEWER", f.id("out1"), "DESCENDANTS"))
        val baptism = EventService.createEvent(f.treeId, owner, CreateEventRequest(personId = f.id("outKid"), type = "BAPTISM", date = "2002", notes = "secret"))
        val drop = client.send(HttpMethod.Delete, "${f.path}/events/${baptism.id}?version=${baptism.version}", Actor.CONTRIBUTOR)
            .decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        assertEquals(
            "BAPTISM",
            client.proposal(f, drop.id, Actor.CONTRIBUTOR).decoded<ChangeProposalDto>().references.single { it.id == baptism.id }.eventType,
        )
        GrantService.deleteGrant(f.treeId, GrantId(viewer.id), owner, viewer.version)
        val hidden = client.proposal(f, drop.id, Actor.CONTRIBUTOR)
        val body = hidden.bodyAsText()
        val mine = json.decodeFromString<ChangeProposalDto>(body)
        assertTrue(mine.references.none { it.id == baptism.id }, "a hidden event is not described")
        assertTrue(mine.fields.all { it.current == null })
        for (secret in listOf("BAPTISM", "secret")) assertFalse(body.contains(secret), secret)
        // The owner still sees which event would be deleted.
        val owners = client.proposal(f, drop.id, Actor.OWNER).decoded<ChangeProposalDto>().references.single { it.id == baptism.id }
        assertEquals("BAPTISM" to "2002", owners.eventType to owners.eventDate)
        assertEquals(f.id("outKid"), owners.personId)
    }

    // --- Revocation, other trees, capabilities -----------------------------------------

    @Test
    fun `a revoked branch-owner grant reviews nothing, and another tree's URL is the generic 404`() = api {
        val f = newFamily()
        val queued = client.proposeRename(f, "aunt", Actor.CONTRIBUTOR, "Tía").decoded<ChangeProposalDto>(HttpStatusCode.Accepted)
        val grant = f.grants.getValue(Actor.BRANCH_OWNER)
        GrantService.deleteGrant(f.treeId, GrantId(grant.id), Actor.OWNER.id, grant.version)
        client.approve(f, queued, Actor.BRANCH_OWNER)
            .expectError(HttpStatusCode.Forbidden, WriteAccess.notReviewerMessage(f.tree), "revoked")
        assertEquals(emptyList(), client.list(f, Actor.BRANCH_OWNER))
        val other = TreeService.createTree(Actor.OTHER_OWNER.id, "Other")
        client.send(
            HttpMethod.Post, "/api/v1/trees/${other.id.value}/proposals/${queued.id}/approve", Actor.OTHER_OWNER,
            encode(ApproveProposalRequest(queued.version)),
        ).expectError(HttpStatusCode.NotFound, notFound(queued.id), "other tree")
        assertEquals("aunt", client.person(f, "aunt").givenNames)
    }

    @Test
    fun `the capability snapshot reports what each member may do`() = api {
        val f = newFamily()
        suspend fun caps(actor: Actor) = client.send(HttpMethod.Get, "${f.path}/capabilities", actor).decoded<TreeCapabilitiesDto>()
        assertEquals(TreeCapabilitiesDto(f.tree, isOwner = true, canEdit = true, canWriteTreeWide = true, canReview = true, proposals = true), caps(Actor.OWNER))
        assertEquals(TreeCapabilitiesDto(f.tree, isOwner = false, canEdit = true, canWriteTreeWide = false, canReview = false, proposals = true), caps(Actor.CONTRIBUTOR))
        assertEquals(TreeCapabilitiesDto(f.tree, isOwner = false, canEdit = false, canWriteTreeWide = false, canReview = false, proposals = true), caps(Actor.VIEWER))
        assertEquals(TreeCapabilitiesDto(f.tree, isOwner = false, canEdit = true, canWriteTreeWide = false, canReview = true, proposals = true), caps(Actor.BRANCH_OWNER))
        // A whole-tree admin edits everything but reviews only through a branch-owner grant.
        assertEquals(TreeCapabilitiesDto(f.tree, isOwner = false, canEdit = true, canWriteTreeWide = true, canReview = false, proposals = true), caps(Actor.ADMIN))
        assertEquals(HttpStatusCode.Forbidden, client.send(HttpMethod.Get, "${f.path}/capabilities", Actor.OTHER_OWNER).status)
    }

    @Test
    fun `with sharing off the snapshot offers no review`() = api(proposals = false) {
        val f = newFamily()
        for (actor in listOf(Actor.OWNER, Actor.BRANCH_OWNER)) {
            val caps = client.send(HttpMethod.Get, "${f.path}/capabilities", actor).decoded<TreeCapabilitiesDto>()
            assertFalse(caps.canReview, "$actor")
            assertFalse(caps.proposals, "$actor")
        }
    }
}
