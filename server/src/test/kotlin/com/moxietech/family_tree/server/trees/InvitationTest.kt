package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreateInvitationRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.CreatedInvitationResponse
import com.moxietech.family_tree.api.InvitationDelivery
import com.moxietech.family_tree.api.InvitationDto
import com.moxietech.family_tree.api.InvitationGrantRequest
import com.moxietech.family_tree.api.LinkMemberPersonRequest
import com.moxietech.family_tree.api.MergePeopleRequest
import com.moxietech.family_tree.api.RedeemInvitationRequest
import com.moxietech.family_tree.api.RedeemInvitationResponse
import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.module
import com.moxietech.family_tree.permissions.HomeBranch
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DEFAULT_API_AUDIENCE
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.config.OidcSettings
import com.moxietech.family_tree.server.config.TestJwks
import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.graph.MERGE_RETIRED_INVITATION_MESSAGE
import com.moxietech.family_tree.server.graph.PersonMergeService
import com.moxietech.family_tree.server.graph.PersonRepository
import com.moxietech.family_tree.server.graph.PersonService
import com.moxietech.family_tree.server.graph.UnionRepository
import com.moxietech.family_tree.server.graph.UnionService
import com.moxietech.family_tree.server.identity.AccountRepository
import com.moxietech.family_tree.server.identity.IdentityTestApi.query
import com.moxietech.family_tree.server.identity.IdentityTestApi.uuid
import com.moxietech.family_tree.server.identity.OidcIdentity
import com.moxietech.family_tree.server.permissions.HomeBranchService
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * FT-011 invitations and account-to-person linking on a disposable PostgreSQL 16
 * container, through the production services and, for the gate, the owner policy and
 * the token-in-body contract, the production routes with test-signed access tokens.
 * Every account is a disposable one created for this run; no mail leaves the process
 * (an in-memory transport and the local `.eml` capture).
 *
 * Family of each test tree:
 *
 *   gp1 + gp2
 *       |
 *   mum + dad
 *       |
 *   me, sib        me + spouse → kid
 *
 * plus an unrelated "stranger" and "meDup" (a duplicate record of me for merges).
 */
class InvitationTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private const val ISSUER = "http://keycloak.test/realms/family-tree"
        private val json = Json { ignoreUnknownKeys = true }

        private lateinit var dataSource: HikariDataSource
        private lateinit var jwks: TestJwks

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
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::jwks.isInitialized) jwks.close()
            if (::dataSource.isInitialized) dataSource.close()
        }

        private fun settings(
            enabled: Boolean = true,
            transport: InvitationSettings.MailTransport = InvitationSettings.MailTransport.NONE,
            captureDir: java.nio.file.Path? = null,
        ) = InvitationSettings(
            enabled = enabled,
            defaultTtl = Duration.ofHours(168),
            maxTtl = Duration.ofHours(720),
            mailTransport = transport,
            mailCaptureDir = captureDir,
            acceptUrl = "http://localhost:3000/",
            mailFrom = "invitations@family-tree.invalid",
        )
    }

    /** A disposable account bound to (ISSUER, subject), with a matching access token. */
    private class Actor(val subject: String) {
        val account: Account = AccountRepository.getOrCreateByIdentity(
            OidcIdentity(ISSUER, subject), "$subject@example.test", "Name $subject", Locale.ES,
        )
        val id get() = account.id
        val token: String get() = jwks.accessToken(ISSUER, email = "$subject@example.test") { withSubject(subject) }
    }

    private fun actor(role: String) = Actor("ft011-$role-${Uuid.random().toHexString().take(8)}")

    private class Family(val tree: TreeId, val owner: Actor) {
        val ids = linkedMapOf<String, String>()
        val treeUuid: Uuid get() = Uuid.parse(tree.value)
        fun id(label: String) = ids.getValue(label)
        fun label(id: String) = ids.entries.firstOrNull { it.value == id }?.key ?: "?$id"
    }

    private suspend fun newFamily(owner: Actor = actor("owner")): Family {
        val f = Family(TreeService.createTree(owner.id, "FT-011 family").id, owner)
        for (label in listOf("gp1", "gp2", "mum", "dad", "me", "sib", "spouse", "kid", "stranger", "meDup")) {
            val given = if (label == "meDup") "me" else label
            f.ids[label] = PersonService.createPerson(f.tree, owner.id, CreatePersonRequest(givenNames = given, sex = "UNKNOWN", isLiving = false))
                .first.id.value
        }
        f.union("gp1", "gp2", "mum")
        f.union("mum", "dad", "me", "sib")
        f.union("me", "spouse", "kid")
        return f
    }

    private suspend fun Family.union(a: String, b: String, vararg kids: String) {
        val union = UnionService.createUnion(tree, owner.id, CreateUnionRequest(id(a), id(b), type = "MARRIED"))
        for (kid in kids) UnionService.addChild(tree, UnionId(union.id), owner.id, AddUnionChildRequest(id(kid), "BIRTH"))
    }

    private fun Family.addMember(actor: Actor, status: MembershipStatus = MembershipStatus.ACTIVE, person: String? = null) =
        MembershipRepository.insert(treeUuid, Uuid.parse(actor.id.value), person?.let { Uuid.parse(id(it)) }, status)

    private fun count(sql: String, vararg args: Any?): Int = dataSource.query(sql, *args)!!.toInt()
    private fun pendingCount(f: Family) = count("select count(*) from invitation where tree_id = ?", uuid(f.tree.value))
    private fun audits(f: Family) = count("select count(*) from audit_log where tree_id = ?", uuid(f.tree.value))
    private fun grants(f: Family) = count("select count(*) from tree_grant where tree_id = ?", uuid(f.tree.value))
    private fun memberships(f: Family) = count("select count(*) from membership where tree_id = ?", uuid(f.tree.value))

    /** Everything an invitation or redemption can write, for "nothing changed" checks. */
    private fun state(f: Family) = listOf(pendingCount(f), audits(f), grants(f), memberships(f))

    private fun sha256Hex(token: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

    private fun service(
        settings: InvitationSettings = settings(),
        mailer: InvitationMailer? = InvitationMailer.from(settings),
        clock: () -> Instant = Instant::now,
    ) = InvitationService(settings, mailer, clock)

    private fun viewerGrant(anchor: String) = InvitationGrantRequest("VIEWER", anchor, "SIDE", depth = 1, includePartners = true)

    /** Every log event from now until [stopCapture], at the configured (INFO) level, request logging included. */
    private fun captureLogs(): ListAppender<ILoggingEvent> =
        ListAppender<ILoggingEvent>().also {
            it.start()
            rootLogger().addAppender(it)
        }

    private fun ListAppender<ILoggingEvent>.stopCapture() {
        rootLogger().detachAppender(this)
        stop()
    }

    private fun rootLogger() = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger

    /**
     * Opens a separate connection that runs [sql] (a `FOR UPDATE` / `FOR NO KEY UPDATE`
     * select) and keeps its transaction open, so writers that need that row wait.
     */
    private fun holdLock(sql: String, vararg args: Any?): Connection =
        dataSource.connection.also { c ->
            c.autoCommit = false
            c.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
                st.executeQuery().use { assertTrue(it.next(), "the row to hold must exist") }
            }
        }

    /** Waits (up to 20 s) until [count] other backends are blocked on a row or advisory lock. */
    private suspend fun awaitLockWaiters(count: Int) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (count("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()") < count) {
            check(System.nanoTime() < deadline) { "expected $count transactions waiting on a lock" }
            delay(20)
        }
    }

    private fun <T> snapshot(block: () -> T): T =
        transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) { block() }

    // HTTP --------------------------------------------------------------------------------

    private fun oidc() = OidcSettings(issuer = ISSUER, jwksUri = jwks.jwksUri, audience = DEFAULT_API_AUDIENCE)

    private fun http(settings: InvitationSettings, service: InvitationService = service(settings), block: suspend HttpClient.() -> Unit) =
        testApplication {
            application { module(oidc(), invitationSettings = settings, invitations = service) }
            client.block()
        }

    /** [actor] null sends no token (an anonymous caller). */
    private suspend fun HttpClient.send(method: String, path: String, actor: Actor?, body: String? = null): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (actor != null) bearerAuth(actor.token)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "GET" -> get(path, build)
            "POST" -> post(path, build)
            "PUT" -> put(path, build)
            "DELETE" -> delete(path, build)
            else -> error(method)
        }
    }

    private suspend fun HttpResponse.expect(status: HttpStatusCode): String = bodyAsText().also { assertEquals(status, this.status, it) }

    // Tests -------------------------------------------------------------------------------

    @Test
    fun `disabled by default - the routes don't exist for anyone and nothing is read or written`() = runBlocking {
        if (System.getenv("INVITATIONS_ENABLED") == null) assertFalse(InvitationSettings().enabled, "invitations must default to off")
        val f = newFamily()
        val member = actor("member").also { f.addMember(it) }
        val live = service().create(f.tree, f.owner.account, CreateInvitationRequest()) // made while on
        val before = state(f)
        val off = settings(enabled = false)
        http(off) {
            val t = "/api/v1/trees/${f.tree.value}"
            val create = json.encodeToString(CreateInvitationRequest(personId = f.id("me")))
            for ((method, path, body) in listOf(
                Triple("POST", "$t/invitations", create),
                Triple("POST", "$t/invitations", "{not json"),
                Triple("GET", "$t/invitations", null),
                Triple("DELETE", "$t/invitations/${live.invitation.id}?version=1", null),
                Triple("POST", "$t/invitations/redeem", json.encodeToString(RedeemInvitationRequest(live.token))),
                Triple("GET", "$t/members", null),
                Triple("PUT", "$t/members/${member.id.value}/person", """{"personId":"${f.id("sib")}","version":1}"""),
            )) {
                // Anonymous callers too: an absent route, not an authentication challenge.
                for (caller in listOf(f.owner, member, actor("stranger"), null)) {
                    send(method, path, caller, body).expect(HttpStatusCode.NotFound)
                }
            }
        }
        assertFailsWith<NotFoundException> { service(off).create(f.tree, f.owner.account, CreateInvitationRequest()) }
        assertFailsWith<NotFoundException> { MemberService(off).list(f.tree, f.owner.id) }
        assertEquals(before, state(f))
    }

    @Test
    fun `only the active owner may invite, list, revoke, see members or link`() = runBlocking {
        val f = newFamily()
        val member = actor("member").also { f.addMember(it) }
        val invitee = actor("invited").also { f.addMember(it, MembershipStatus.INVITED) }
        val outsider = actor("outsider")
        val created = service().create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("sib")))
        val before = state(f)
        http(settings()) {
            val t = "/api/v1/trees/${f.tree.value}"
            for (caller in listOf(member, invitee, outsider)) {
                send("POST", "$t/invitations", caller, json.encodeToString(CreateInvitationRequest())).expect(HttpStatusCode.Forbidden)
                send("POST", "$t/invitations", caller, "{not json").expect(HttpStatusCode.Forbidden)
                send("GET", "$t/invitations", caller).expect(HttpStatusCode.Forbidden)
                send("DELETE", "$t/invitations/${created.invitation.id}?version=1", caller).expect(HttpStatusCode.Forbidden)
                send("GET", "$t/members", caller).expect(HttpStatusCode.Forbidden)
                send("PUT", "$t/members/${member.id.value}/person", caller, """{"personId":null,"version":1}""").expect(HttpStatusCode.Forbidden)
            }
        }
        assertFailsWith<ForbiddenException> { service().create(f.tree, member.account, CreateInvitationRequest()) }
        assertEquals(before, state(f))
    }

    @Test
    fun `a link invitation is redeemed once over HTTP and links the person and the proposed grants`() = runBlocking {
        val f = newFamily()
        val other = newFamily()
        val redeemer = actor("redeemer")
        val second = actor("second")
        val logs = captureLogs()
        lateinit var token: String
        lateinit var digest: String
        try {
        http(settings()) {
            val t = "/api/v1/trees/${f.tree.value}"
            val request = CreateInvitationRequest(personId = f.id("me"), grants = listOf(viewerGrant(f.id("gp1"))), expiresInHours = 48)
            val created = json.decodeFromString<CreatedInvitationResponse>(
                send("POST", "$t/invitations", f.owner, json.encodeToString(request)).expect(HttpStatusCode.Created),
            )
            token = created.token
            assertEquals(InvitationDelivery.NOT_REQUESTED, created.delivery)
            assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(created.token))
            // The link carries everything the join page needs, all of it in the fragment.
            assertEquals("http://localhost:3000/#/trees/${f.tree.value}/join?token=${created.token}", created.link)

            // A token presented for another tree, an unknown tree or a malformed tree ID is the
            // same miss as a random token, and leaves the invitation usable in its own tree.
            val body = json.encodeToString(RedeemInvitationRequest(created.token))
            val before = state(f)
            for (path in listOf("/api/v1/trees/${other.tree.value}", "/api/v1/trees/${Uuid.random()}", "/api/v1/trees/not-a-uuid", t)) {
                val tokenBody = if (path == t) json.encodeToString(RedeemInvitationRequest("b".repeat(43))) else body
                assertContains(send("POST", "$path/invitations/redeem", redeemer, tokenBody).expect(HttpStatusCode.NotFound), INVITATION_NOT_FOUND_MESSAGE)
            }
            assertEquals(before, state(f))
            assertNull(MembershipRepository.find(other.treeUuid, Uuid.parse(redeemer.id.value)))

            // Only the digest is stored; the token is nowhere in the database or the owner's list.
            digest = dataSource.query("select encode(token_digest, 'hex') from invitation where id = ?", uuid(created.invitation.id))!!
            assertEquals(sha256Hex(created.token), digest)
            assertEquals(0, count("select count(*) from audit_log where before_json like ? or after_json like ?", "%${created.token}%", "%${created.token}%"))
            assertEquals(0, count("select count(*) from audit_log where after_json like ?", "%$digest%"))
            val listed = send("GET", "$t/invitations", f.owner).expect(HttpStatusCode.OK)
            assertFalse(created.token in listed)
            assertEquals(f.id("me"), json.decodeFromString<List<InvitationDto>>(listed).single().personId)

            // Accept: the token travels in the body only.
            val redeemed = json.decodeFromString<RedeemInvitationResponse>(
                send("POST", "$t/invitations/redeem", redeemer, body).expect(HttpStatusCode.OK),
            )
            assertEquals(f.id("me"), redeemed.member.personId)
            assertEquals("ACTIVE", redeemed.member.status)
            val grant = redeemed.grants.single()
            assertEquals(listOf("VIEWER", f.id("gp1"), "SIDE", "1", "true"), listOf(grant.role, grant.anchorPersonId, grant.direction, grant.depth.toString(), grant.includePartners.toString()))
            assertEquals(redeemer.id.value, grant.accountId)
            assertEquals(f.owner.id.value, dataSource.query("select created_by_account_id from tree_grant where id = ?", uuid(grant.id)))
            assertEquals(0, pendingCount(f))
            assertEquals(
                "Grant:CREATE|Invitation:CREATE|Invitation:DELETE|Membership:CREATE",
                dataSource.query(
                    "select string_agg(entity_type || ':' || action, '|' order by entity_type, action) from audit_log " +
                        "where tree_id = ? and entity_type in ('Invitation', 'Membership', 'Grant')",
                    uuid(f.tree.value),
                ),
            )
            assertContains(
                dataSource.query("select after_json from audit_log where entity_type = 'Invitation' and action = 'DELETE' and entity_id = ?", created.invitation.id)!!,
                "ACCEPTED",
            )

            // Once only, whoever presents it next.
            for (caller in listOf(redeemer, second)) {
                assertContains(
                    send("POST", "$t/invitations/redeem", caller, body).expect(HttpStatusCode.NotFound),
                    INVITATION_NOT_FOUND_MESSAGE,
                )
            }

            // Phase 1 policy still applies: the new member reads, and only the owner writes.
            send("GET", t, redeemer).expect(HttpStatusCode.OK)
            send("POST", "$t/places", redeemer, """{"name":"Sevilla"}""").expect(HttpStatusCode.Forbidden)
            send("POST", "$t/invitations", redeemer, json.encodeToString(CreateInvitationRequest())).expect(HttpStatusCode.Forbidden)
        }
        } finally {
            logs.stopCapture()
        }
        // Every request above was logged (success and error alike); none carries the token or its digest.
        val logged = logs.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        assertTrue(logged.any { "/invitations/redeem" in it }, "the redemption requests were logged")
        assertTrue(logged.none { token in it || digest in it.lowercase() }, "a log line leaked the token or its digest")
        assertEquals(0, count("select count(*) from audit_log where before_json like ? or after_json like ?", "%$token%", "%$token%"))
        assertEquals(0, count("select count(*) from audit_log where lower(coalesce(before_json, '') || coalesce(after_json, '')) like ?", "%$digest%"))

        // The home branch follows the linked person: parents, their descendants and partners.
        val stored = snapshot { HomeBranchService.homeBranchInSnapshot(f.treeUuid, Uuid.parse(redeemer.id.value)) }
        val reference = HomeBranch.resolve(
            PersonId(f.id("me")),
            PersonRepository.listByTree(f.treeUuid).map { it.id },
            UnionRepository.listByTree(f.treeUuid),
            UnionRepository.childrenByUnion(f.treeUuid).values.flatten(),
        )
        assertEquals(reference.map { it.value }.toSet(), stored.map { it.toString() }.toSet())
        assertEquals(setOf("me", "mum", "dad", "sib", "spouse", "kid"), stored.map { f.label(it.toString()) }.toSet())
    }

    @Test
    fun `expired, revoked, malformed and unknown tokens are the same 404 and write nothing`() = runBlocking {
        val f = newFamily()
        var now = Instant.parse("2026-11-17T10:00:00Z")
        val invitations = service(clock = { now })
        val redeemer = actor("late")
        val expiring = invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("me"), expiresInHours = 1))
        val revoked = invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("sib")))
        invitations.revoke(f.tree, revoked.invitation.id, f.owner.id, listOf("1"))
        now = now.plus(Duration.ofHours(1))
        assertTrue(invitations.list(f.tree, f.owner.id).single().expired)
        val before = state(f)

        for (token in listOf(expiring.token, revoked.token, "short", "a".repeat(43), "${expiring.token}x", "")) {
            val e = assertFailsWith<NotFoundException> { invitations.redeem(f.tree.value, redeemer.account, RedeemInvitationRequest(token)) }
            assertEquals(INVITATION_NOT_FOUND_MESSAGE, e.message)
        }
        assertEquals(before, state(f))
        assertNull(MembershipRepository.find(f.treeUuid, Uuid.parse(redeemer.id.value)))

        // The expired invitation still holds its person: a new one for "me" is refused until
        // the owner, who sees it marked expired, revokes it; then the replacement works.
        val blocked = assertFailsWith<ConflictException> {
            invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("me")))
        }
        assertEquals(INVITATION_PERSON_PENDING_MESSAGE, blocked.message)
        assertFailsWith<ConflictException> { invitations.revoke(f.tree, expiring.invitation.id, f.owner.id, listOf("2")) }
        invitations.revoke(f.tree, expiring.invitation.id, f.owner.id, listOf("1"))
        assertEquals(0, pendingCount(f))
        val replacement = invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("me")))
        assertEquals(f.id("me"), invitations.redeem(f.tree.value, redeemer.account, RedeemInvitationRequest(replacement.token)).member.personId)
    }

    @Test
    fun `an invitation that expires while redemption waits for its lock is refused`() = runBlocking {
        val f = newFamily()
        // Read from the redemption's thread after the lock wait, so it must be visible across threads.
        val now = java.util.concurrent.atomic.AtomicReference(Instant.parse("2026-11-17T10:00:00Z"))
        val invitations = service(clock = { now.get() })
        val created = invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("me"), expiresInHours = 1))
        val before = state(f)

        // Another transaction holds the invitation; the redemption queues behind it while valid.
        val holder = holdLock("select 1 from invitation where id = ? for update", uuid(created.invitation.id))
        val redeeming = async(Dispatchers.IO) {
            runCatching { invitations.redeem(f.tree.value, actor("waiting").account, RedeemInvitationRequest(created.token)) }
        }
        try {
            awaitLockWaiters(1)
            now.set(now.get().plus(Duration.ofHours(2))) // expires while it waits
        } finally {
            holder.commit()
            holder.close()
        }
        val outcome = redeeming.await()
        assertIs<NotFoundException>(outcome.exceptionOrNull(), outcome.toString())
        assertEquals(before, state(f))
        // Still there for the owner to see and revoke.
        assertTrue(invitations.list(f.tree, f.owner.id).single().expired)
        invitations.revoke(f.tree, created.invitation.id, f.owner.id, listOf("1"))
    }

    @Test
    fun `creating an invitation and linking a member race for one person and exactly one wins`() = runBlocking {
        repeat(2) { round ->
            val f = newFamily()
            val invitations = service()
            val members = MemberService(settings())
            val member = actor("racing").also { f.addMember(it) }
            val before = state(f)

            // Both writers queue on the person's row; whichever gets it first wins, and the
            // other re-checks after that commit and gets a 409 with nothing written.
            val holder = holdLock("select 1 from person where id = ? for no key update", uuid(f.id("sib")))
            val create = async(Dispatchers.IO) {
                runCatching { invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("sib"))) }
            }
            if (round == 0) awaitLockWaiters(1) // round 0: the invitation queues first; round 1: both at once
            val link = async(Dispatchers.IO) {
                runCatching { members.linkPerson(f.tree, member.id.value, f.owner.id, LinkMemberPersonRequest(f.id("sib"), version = 1)) }
            }
            try {
                awaitLockWaiters(2)
            } finally {
                holder.commit()
                holder.close()
            }
            val (created, linked) = create.await() to link.await()

            assertTrue(created.isSuccess != linked.isSuccess, "create=$created link=$linked")
            assertIs<ConflictException>((if (created.isSuccess) linked else created).exceptionOrNull())
            val invitationsForSib = count("select count(*) from invitation where tree_id = ? and person_id = ?", uuid(f.tree.value), uuid(f.id("sib")))
            val membersForSib = count("select count(*) from membership where tree_id = ? and person_id = ?", uuid(f.tree.value), uuid(f.id("sib")))
            assertEquals(1, invitationsForSib + membersForSib, "the person is promised exactly once")
            assertEquals(if (created.isSuccess) 1 else 0, invitationsForSib)
            // One write and its one audit entry, nothing from the loser.
            assertEquals(before[1] + 1, audits(f))
            assertEquals(before[2], grants(f))
            assertEquals(before[3], memberships(f))
        }
    }

    @Test
    fun `members, linked people and pending people are conflicts`() = runBlocking {
        val f = newFamily()
        val invitations = service()
        val linked = actor("linked").also { f.addMember(it, person = "sib") }

        // Creating: a person already linked to a member, or already invited.
        assertFailsWith<ConflictException> { invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("sib"))) }
        invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("me")))
        assertFailsWith<ConflictException> { invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("me"))) }
        // References must be people of this tree.
        val other = newFamily()
        assertFailsWith<NotFoundException> { invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = other.id("me"))) }
        assertFailsWith<NotFoundException> {
            invitations.create(f.tree, f.owner.account, CreateInvitationRequest(grants = listOf(viewerGrant(other.id("gp1")))))
        }
        assertFailsWith<ValidationException> {
            invitations.create(f.tree, f.owner.account, CreateInvitationRequest(grants = listOf(InvitationGrantRequest("OWNER", f.id("gp1"), "SIDE"))))
        }
        assertFailsWith<ValidationException> { invitations.create(f.tree, f.owner.account, CreateInvitationRequest(expiresInHours = 721)) }

        // Redeeming: an existing active member (the owner included) can't redeem.
        val link = invitations.create(f.tree, f.owner.account, CreateInvitationRequest())
        val before = state(f)
        for (member in listOf(f.owner, linked)) {
            val e = assertFailsWith<ConflictException> { invitations.redeem(f.tree.value, member.account, RedeemInvitationRequest(link.token)) }
            assertEquals(INVITATION_ALREADY_MEMBER_MESSAGE, e.message)
        }
        assertEquals(before, state(f))

        // An INVITED membership (pre-FT-011 data) is activated, not duplicated.
        val legacy = actor("legacy").also { f.addMember(it, MembershipStatus.INVITED) }
        val result = invitations.redeem(f.tree.value, legacy.account, RedeemInvitationRequest(link.token))
        assertEquals("ACTIVE|2", "${result.member.status}|${result.member.version}")
    }

    @Test
    fun `email invitations are captured locally and not redeemable until the rule is decided`() = runBlocking {
        val f = newFamily()
        val dir = Files.createTempDirectory("ft011-mail")
        val capturing = service(settings(transport = InvitationSettings.MailTransport.CAPTURE, captureDir = dir))
        val created = capturing.create(f.tree, f.owner.account, CreateInvitationRequest(email = "  relative@example.test ", personId = f.id("me")))
        assertEquals(InvitationDelivery.SENT, created.delivery)
        assertEquals("relative@example.test", created.invitation.email)

        val mail = Files.readString(dir.resolve("${created.invitation.id}.eml"))
        assertContains(mail, "To: relative@example.test\r\n")
        assertContains(mail, "http://localhost:3000/#/trees/${f.tree.value}/join?token=${created.token}")
        assertEquals(created.link, mail.lineSequence().single { it.startsWith("http://localhost:3000/#") }.trim())
        assertContains(mail, "Subject: =?UTF-8?B?") // Spanish subject (the owner's locale), RFC 2047
        assertContains(mail, "Name ${f.owner.subject}")
        // The address isn't audited.
        assertEquals(0, count("select count(*) from audit_log where after_json like '%relative@example.test%'"))

        val before = state(f)
        // The token alone never claims an email-addressed invitation, whoever presents it.
        val e = assertFailsWith<ConflictException> { capturing.redeem(f.tree.value, actor("emailed").account, RedeemInvitationRequest(created.token)) }
        assertEquals(INVITATION_EMAIL_REDEMPTION_PENDING_MESSAGE, e.message)
        assertEquals(before, state(f))

        // No transport: email invitations are refused before anything is written.
        assertFailsWith<ValidationException> { service().create(f.tree, f.owner.account, CreateInvitationRequest(email = "x@example.test")) }
        assertFailsWith<ValidationException> { capturing.create(f.tree, f.owner.account, CreateInvitationRequest(email = "a@b.c\r\nBcc: x@y.z")) }
        assertEquals(before, state(f))

        // A failing transport keeps the invitation and reports it, without leaking the token.
        val failing = service(settings(transport = InvitationSettings.MailTransport.CAPTURE, captureDir = dir), mailer = InvitationMailer { error("smtp down") })
        val kept = failing.create(f.tree, f.owner.account, CreateInvitationRequest(email = "y@example.test", locale = "EN"))
        assertEquals(InvitationDelivery.FAILED, kept.delivery)
        assertEquals(2, pendingCount(f))
    }

    @Test
    fun `pending invitations block deleting or merging their people until revoked`() = runBlocking {
        val f = newFamily()
        val invitations = service()
        val created = invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("meDup"), grants = listOf(viewerGrant(f.id("stranger")))))

        for (label in listOf("meDup", "stranger")) {
            val person = PersonRepository.findById(f.treeUuid, Uuid.parse(f.id(label)))!!
            assertFailsWith<ConflictException> { PersonService.deletePerson(f.tree, PersonId(f.id(label)), f.owner.id, listOf(person.version.toString())) }
        }
        val merge = MergePeopleRequest(f.id("me"), PersonRepository.findById(f.treeUuid, Uuid.parse(f.id("me")))!!.version, f.id("meDup"), 1, emptyMap())
        val e = assertFailsWith<ConflictException> { PersonMergeService.merge(f.tree, f.owner.id, merge) }
        assertEquals(MERGE_RETIRED_INVITATION_MESSAGE, e.message)

        invitations.revoke(f.tree, created.invitation.id, f.owner.id, listOf("1"))
        PersonMergeService.merge(f.tree, f.owner.id, merge)
        PersonService.deletePerson(f.tree, PersonId(f.id("stranger")), f.owner.id, listOf("1"))
    }

    @Test
    fun `concurrent redemptions accept the invitation exactly once`() = runBlocking {
        val f = newFamily()
        val invitations = service()
        repeat(5) {
            val created = invitations.create(f.tree, f.owner.account, CreateInvitationRequest(grants = listOf(viewerGrant(f.id("gp1")))))
            val racers = List(4) { actor("racer") }
            val outcomes = racers.map { racer ->
                async(Dispatchers.IO) { runCatching { invitations.redeem(f.tree.value, racer.account, RedeemInvitationRequest(created.token)) } }
            }.awaitAll()
            assertEquals(1, outcomes.count { it.isSuccess }, outcomes.toString())
            assertTrue(outcomes.filter { it.isFailure }.all { it.exceptionOrNull() is NotFoundException }, outcomes.toString())
            val winner = outcomes.single { it.isSuccess }.getOrThrow().member.accountId
            assertEquals(1, count("select count(*) from tree_grant where tree_id = ? and account_id = ?", uuid(f.tree.value), uuid(winner)))
        }
        assertEquals(0, pendingCount(f))
        assertEquals(6, memberships(f)) // owner + one winner per round

        // Redemption against revocation: one of them wins, never both.
        val created = invitations.create(f.tree, f.owner.account, CreateInvitationRequest())
        val redeemer = actor("vs-revoke")
        val (redeem, revoke) = listOf(
            async(Dispatchers.IO) { runCatching { invitations.redeem(f.tree.value, redeemer.account, RedeemInvitationRequest(created.token)) } },
            async(Dispatchers.IO) { runCatching { invitations.revoke(f.tree, created.invitation.id, f.owner.id, listOf("1")) } },
        ).awaitAll()
        assertTrue(redeem.isSuccess != revoke.isSuccess, "redeem=$redeem revoke=$revoke")
        assertEquals(redeem.isSuccess, MembershipRepository.find(f.treeUuid, Uuid.parse(redeemer.id.value)) != null)
    }

    @Test
    fun `the owner links and unlinks a member's person at its version`() = runBlocking {
        val f = newFamily()
        val members = MemberService(settings())
        val invitations = service()
        val member = actor("linkee").also { f.addMember(it) }
        val memberUuid = Uuid.parse(member.id.value)
        assertEquals(emptySet(), snapshot { HomeBranchService.homeBranchInSnapshot(f.treeUuid, memberUuid) }, "unlinked: no home branch")

        val linked = members.linkPerson(f.tree, member.id.value, f.owner.id, LinkMemberPersonRequest(f.id("stranger"), version = 1))
        assertEquals(f.id("stranger") to 2L, linked.personId to linked.version)
        // A parentless person: just themselves, never the whole tree.
        assertEquals(setOf(f.id("stranger")), snapshot { HomeBranchService.homeBranchInSnapshot(f.treeUuid, memberUuid) }.map { it.toString() }.toSet())

        assertFailsWith<ConflictException> { members.linkPerson(f.tree, member.id.value, f.owner.id, LinkMemberPersonRequest(f.id("sib"), version = 1)) }
        val other = newFamily()
        assertFailsWith<NotFoundException> { members.linkPerson(f.tree, member.id.value, f.owner.id, LinkMemberPersonRequest(other.id("me"), version = 2)) }
        assertFailsWith<NotFoundException> { members.linkPerson(f.tree, Uuid.random().toString(), f.owner.id, LinkMemberPersonRequest(null, version = 1)) }
        assertFailsWith<NotFoundException> { members.linkPerson(other.tree, member.id.value, other.owner.id, LinkMemberPersonRequest(null, version = 1)) }

        // A person linked to someone else, or named by a pending invitation, can't be linked.
        val owner = MembershipRepository.find(f.treeUuid, Uuid.parse(f.owner.id.value))!!
        members.linkPerson(f.tree, f.owner.id.value, f.owner.id, LinkMemberPersonRequest(f.id("gp1"), owner.version))
        assertFailsWith<ConflictException> { members.linkPerson(f.tree, member.id.value, f.owner.id, LinkMemberPersonRequest(f.id("gp1"), version = 2)) }
        invitations.create(f.tree, f.owner.account, CreateInvitationRequest(personId = f.id("sib")))
        assertFailsWith<ConflictException> { members.linkPerson(f.tree, member.id.value, f.owner.id, LinkMemberPersonRequest(f.id("sib"), version = 2)) }

        val unlinked = members.linkPerson(f.tree, member.id.value, f.owner.id, LinkMemberPersonRequest(null, version = 2))
        assertNull(unlinked.personId)
        assertEquals(emptySet(), snapshot { HomeBranchService.homeBranchInSnapshot(f.treeUuid, memberUuid) })
        assertEquals(2, count("select count(*) from audit_log where tree_id = ? and entity_type = 'Membership' and entity_id = ?", uuid(f.tree.value), "${f.tree.value}:${member.id.value}"))
        assertEquals(listOf(f.owner.id.value, member.id.value).sorted(), members.list(f.tree, f.owner.id).map { it.accountId }.sorted())
    }

    @Test
    fun `an invitation whose creator is no longer the active owner is void`() = runBlocking {
        val f = newFamily()
        val invitations = service()
        val created = invitations.create(f.tree, f.owner.account, CreateInvitationRequest())
        dataSource.query("update membership set status = 'INVITED' where tree_id = ? and account_id = ? returning 1", uuid(f.tree.value), uuid(f.owner.id.value))
        try {
            val before = state(f)
            assertFailsWith<NotFoundException> { invitations.redeem(f.tree.value, actor("void").account, RedeemInvitationRequest(created.token)) }
            assertEquals(before, state(f))
        } finally {
            dataSource.query("update membership set status = 'ACTIVE' where tree_id = ? and account_id = ? returning 1", uuid(f.tree.value), uuid(f.owner.id.value))
        }
    }
}
