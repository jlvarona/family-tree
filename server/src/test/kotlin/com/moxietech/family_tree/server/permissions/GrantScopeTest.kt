package com.moxietech.family_tree.server.permissions

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreateGrantRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.GrantDto
import com.moxietech.family_tree.api.MergePeopleRequest
import com.moxietech.family_tree.api.UnionDto
import com.moxietech.family_tree.api.UpdateGrantRequest
import com.moxietech.family_tree.api.UpdatePersonRequest
import com.moxietech.family_tree.api.UpdateUnionRequest
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Branch
import com.moxietech.family_tree.domain.BranchDirection
import com.moxietech.family_tree.domain.GrantId
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.permissions.BranchResolution
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.errors.ConflictException
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.graph.MERGE_RETIRED_GRANT_ANCHOR_MESSAGE
import com.moxietech.family_tree.server.graph.PersonMergeService
import com.moxietech.family_tree.server.graph.PersonRepository
import com.moxietech.family_tree.server.graph.PersonService
import com.moxietech.family_tree.server.graph.UnionRepository
import com.moxietech.family_tree.server.graph.UnionService
import com.moxietech.family_tree.server.identity.IdentityTestApi.query
import com.moxietech.family_tree.server.identity.IdentityTestApi.uuid
import com.moxietech.family_tree.server.trees.MembershipRepository
import com.moxietech.family_tree.server.trees.TreeService
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.postgresql.util.PSQLException
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * FT-010 grants and grant scopes on a disposable PostgreSQL 16 container migrated by
 * Flyway, through the production services (no HTTP routes exist for grants yet).
 *
 * Family used by most tests (generations top to bottom), the same graph as
 * `BranchResolutionTest` in :core:
 *
 *   gg1 + gg2
 *       |
 *   gp1 + gp2          gpX (gp2's other partner, no children)
 *       |
 *   mum + dad          dadParent (dad's own parent: partner lineage)
 *       |
 *   me + spouse        spouseKid (spouse's child with exSpouse)
 *       |
 *   kid
 *       |  (adopted)
 *   grandkid
 *
 * plus an unrelated "stranger". The side tests add aunt (gp1 + gp2's other child),
 * cousin (aunt's child) and spouseMum (spouse's parent). OWNER owns every tree; MEMBER
 * is an active non-owner member holding the grants; OTHER owns tree B.
 */
class GrantScopeTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private lateinit var dataSource: HikariDataSource
        private lateinit var owner: AccountId
        private lateinit var member: AccountId
        private lateinit var other: AccountId

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dataSource = DatabaseConfig.createDataSource(
                DatabaseSettings(jdbcUrl = postgres.jdbcUrl, user = postgres.username, password = postgres.password),
            )
            DatabaseConfig.migrate(dataSource)
            DatabaseConfig.connectExposed(dataSource)
            AppDatabase.init(dataSource)
            owner = account("ft010-owner")
            member = account("ft010-member")
            other = account("ft010-other")
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::dataSource.isInitialized) dataSource.close()
        }

        private fun account(subject: String): AccountId = AccountId(
            dataSource.query(
                "insert into account (email, display_name, oidc_issuer, oidc_subject) values (?, ?, 'http://test', ?) returning id",
                "$subject@example.com", subject, subject,
            )!!,
        )

        private val LABELS = listOf(
            "gg1", "gg2", "gp1", "gp2", "gpX", "mum", "dad", "dadParent", "me", "spouse", "exSpouse",
            "spouseKid", "kid", "grandkid", "stranger",
        )
    }

    /** One tree with labelled people. */
    private class Family(val tree: TreeId) {
        val ids = linkedMapOf<String, String>()
        val treeUuid: Uuid get() = Uuid.parse(tree.value)
        fun id(label: String) = ids.getValue(label)
        fun label(id: String) = ids.entries.firstOrNull { it.value == id }?.key ?: "?$id"
    }

    private suspend fun newTree(name: String, treeOwner: AccountId = owner): Family {
        val tree = TreeService.createTree(treeOwner, name)
        if (treeOwner == owner) {
            MembershipRepository.insert(Uuid.parse(tree.id.value), Uuid.parse(member.value), null, MembershipStatus.ACTIVE)
        }
        return Family(tree.id)
    }

    private suspend fun newFamily(name: String = "FT-010 family"): Family = newTree(name).apply {
        LABELS.forEach { person(it) }
        union("gg1", "gg2", "gp1")
        union("gpX", "gp2")
        union("gp1", "gp2", "mum")
        union("dadParent", null, "dad")
        union("mum", "dad", "me")
        union("me", "spouse", "kid")
        union("spouse", "exSpouse", "spouseKid")
        union("kid", null, "grandkid", relation = "ADOPTED")
    }

    private suspend fun Family.person(label: String, given: String = label, actor: AccountId = owner): String {
        val id = PersonService.createPerson(tree, actor, CreatePersonRequest(givenNames = given, sex = "UNKNOWN", isLiving = false))
            .first.id.value
        ids[label] = id
        return id
    }

    private suspend fun Family.union(a: String, b: String?, vararg kids: String, relation: String = "BIRTH"): UnionDto {
        var union = UnionService.createUnion(tree, owner, CreateUnionRequest(id(a), b?.let(::id), type = "MARRIED"))
        for (kid in kids) union = UnionService.addChild(tree, UnionId(union.id), owner, AddUnionChildRequest(id(kid), relation))
        return union
    }

    private suspend fun Family.unionOf(a: String, b: String?): UnionDto =
        UnionRepository.listByTree(treeUuid).single { it.partner1Id.value == id(a) && it.partner2Id?.value == b?.let(::id) }
            .let { union -> UnionService.listUnions(tree, owner).single { it.id == union.id.value } }

    private suspend fun Family.grant(
        anchor: String,
        direction: String,
        depth: Int? = null,
        partners: Boolean = false,
        holder: AccountId = member,
        role: String = "VIEWER",
    ): GrantDto = GrantService.createGrant(tree, owner, CreateGrantRequest(holder.value, role, id(anchor), direction, depth, partners))

    private suspend fun Family.addSideRelatives() {
        person("aunt")
        person("cousin")
        person("spouseMum")
        UnionService.addChild(tree, UnionId(unionOf("gp1", "gp2").id), owner, AddUnionChildRequest(id("aunt"), "BIRTH"))
        union("aunt", null, "cousin")
        union("spouseMum", null, "spouse")
    }

    /** Refreshes the cached scope (the write-capable path) and returns it by label. */
    private suspend fun Family.scope(grant: GrantDto): Set<String> =
        GrantScopeService.refreshScope(treeUuid, Uuid.parse(grant.id)).map { label(it.toString()) }.toSet()

    /** Runs [block] in a read-only REPEATABLE READ transaction, as an authorization read would. */
    private fun <T> snapshot(block: () -> T): T =
        transaction(transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ, readOnly = true) { block() }

    private fun Family.snapshotScope(grant: GrantDto): Set<String> =
        snapshot { GrantScopeService.scopeInSnapshot(treeUuid, Uuid.parse(grant.id)) }.map { label(it.toString()) }.toSet()

    /** The same branch resolved by :core over the stored graph. */
    private fun Family.reference(grant: GrantDto): Set<String> {
        val people = PersonRepository.listByTree(treeUuid).map { it.id }
        val unions = UnionRepository.listByTree(treeUuid)
        val children = UnionRepository.childrenByUnion(treeUuid).values.flatten()
        val branch = Branch(PersonId(grant.anchorPersonId), BranchDirection.valueOf(grant.direction), grant.depth, grant.includePartners)
        return BranchResolution.resolve(branch, people, unions, children).map { label(it.value) }.toSet()
    }

    private fun generation(f: Family): Long =
        dataSource.query("select generation from tree_graph_generation where tree_id = ?", uuid(f.tree.value))!!.toLong()

    /** "grantVersion:generation" of the grant's cached scope, or null when none is stored. */
    private fun stamp(grant: GrantDto): String? =
        dataSource.query("select grant_version || ':' || graph_generation from grant_scope_state where grant_id = ?", uuid(grant.id))

    private fun count(sql: String, vararg args: Any?): Long = dataSource.query(sql, *args)!!.toLong()

    private fun execute(sql: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private val tenantTables = listOf("person", "union_record", "union_child", "membership", "tree_grant", "grant_scope", "grant_scope_state", "audit_log")

    private fun fingerprint(f: Family): Map<String, String?> = tenantTables.associateWith { table ->
        dataSource.query("select string_agg(t::text, E'\\n' order by t::text) from $table t where tree_id = ?", uuid(f.tree.value))
    }

    private fun grantAudits(grantId: String): List<String> = dataSource.connection.use { c ->
        c.prepareStatement("select action from audit_log where entity_type = 'Grant' and entity_id = ? order by at").use { st ->
            st.setString(1, grantId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    /** Waits, at most 10 s, until [count] other sessions wait on a lock in a statement matching `%[pattern]%`. */
    private suspend fun awaitBlocked(pattern: String, count: Int = 1) = withTimeout(10_000) {
        val sql = """
            select count(*) from pg_stat_activity
            where pid <> pg_backend_pid() and wait_event_type = 'Lock' and query ilike ?
        """.trimIndent()
        while (count(sql, "%$pattern%") < count) delay(25)
    }

    /** Runs [block] while a test session holds [lockSql] (with one uuid parameter when [id] is given) in an open transaction. */
    private suspend fun <T> whileLocked(lockSql: String, id: String? = null, block: suspend CoroutineScope.(release: () -> Unit) -> T): T {
        val lock = dataSource.connection
        try {
            lock.autoCommit = false
            lock.prepareStatement(lockSql).use { st ->
                if (id != null) st.setObject(1, uuid(id))
                st.execute()
            }
            return coroutineScope { block { lock.rollback() } }
        } finally {
            runCatching { lock.rollback() }
            lock.close()
        }
    }

    // --- Resolution ---------------------------------------------------------------------

    @Test
    fun `scopes follow anchor, direction, depth and direct partners only`() = runBlocking {
        val f = newFamily()
        assertEquals(setOf("me", "mum", "dad"), f.scope(f.grant("me", "ANCESTORS", depth = 1)))
        assertEquals(setOf("me", "mum", "dad", "gp1", "gp2", "dadParent"), f.scope(f.grant("me", "ANCESTORS", depth = 2)))
        assertEquals(setOf("me", "mum", "dad", "gp1", "gp2", "dadParent", "gg1", "gg2"), f.scope(f.grant("me", "ANCESTORS")))
        assertEquals(setOf("me", "kid"), f.scope(f.grant("me", "DESCENDANTS", depth = 1)))
        // Every child relation counts (grandkid is adopted).
        assertEquals(setOf("me", "kid", "grandkid"), f.scope(f.grant("me", "DESCENDANTS")))
        // Partners: the spouse joins; the spouse's other child and other partner don't.
        assertEquals(setOf("me", "spouse", "kid", "grandkid"), f.scope(f.grant("me", "DESCENDANTS", partners = true)))
        // An ancestor's other partner joins as a partner; nothing above or below that partner does.
        val up = f.scope(f.grant("mum", "ANCESTORS", partners = true))
        assertTrue("gpX" in up && "dad" in up, up.toString())
        assertTrue("dadParent" !in up && "me" !in up && "stranger" !in up, up.toString())
        assertEquals(LABELS.toSet(), f.scope(f.grant("stranger", "WHOLE_TREE")))
        assertEquals(LABELS.toSet(), f.scope(f.grant("stranger", "WHOLE_TREE", depth = 1)), "whole tree ignores depth")
    }

    @Test
    fun `a side caps only the ascent and takes every descendant of those ancestors`() = runBlocking {
        val f = newFamily("FT-010 side")
        f.addSideRelatives()
        // Depth 1: grandparents are just beyond the cap, so aunt and cousin are out; grandkid,
        // two generations below the anchor, is in.
        assertEquals(setOf("me", "mum", "dad", "kid", "grandkid"), f.scope(f.grant("me", "SIDE", depth = 1)))
        // Depth 2: grandparents and their other descendants (aunt, cousin) join; gg1 stays out.
        val two = setOf("me", "mum", "dad", "gp1", "gp2", "dadParent", "aunt", "cousin", "kid", "grandkid")
        assertEquals(two, f.scope(f.grant("me", "SIDE", depth = 2)))
        assertEquals(two + setOf("gg1", "gg2"), f.scope(f.grant("me", "SIDE")))
        // Partners come last and only directly: never the spouse's parent, other partner or other child.
        assertEquals(two + setOf("spouse", "gpX"), f.scope(f.grant("me", "SIDE", depth = 2, partners = true)))
        // An anchor without recorded parents still brings its descendants.
        assertEquals(setOf("spouseMum", "spouse", "kid", "grandkid", "spouseKid"), f.scope(f.grant("spouseMum", "SIDE", depth = 3)))
    }

    @Test
    fun `a huge finite depth on an ancestry cycle resolves promptly to the unlimited result`() = runBlocking {
        val f = newTree("FT-010 two-person cycle")
        f.person("a")
        f.person("b")
        f.union("a", null, "b")
        f.union("b", null, "a")
        for (direction in listOf("ANCESTORS", "DESCENDANTS", "SIDE")) {
            for (partners in listOf(false, true)) {
                val grant = f.grant("a", direction, depth = Int.MAX_VALUE, partners = partners)
                // A leg that kept counting generations would need billions of steps; this
                // statement must finish well inside its own short timeout.
                val resolved = snapshot {
                    withJdbc { c -> c.createStatement().use { it.execute("set local statement_timeout = '5s'") } }
                    GrantScopeService.scopeInSnapshot(f.treeUuid, Uuid.parse(grant.id))
                }
                assertEquals(setOf("a", "b"), resolved.map { f.label(it.toString()) }.toSet(), "$direction partners=$partners")
                assertEquals(f.reference(grant), f.snapshotScope(grant), "$direction partners=$partners")
            }
        }
    }

    @Test
    fun `the CTE agrees with the core reference rules for every definition`() = runBlocking {
        val f = newFamily("FT-010 matrix")
        f.addSideRelatives()
        for (anchor in listOf("me", "gp1", "spouse", "mum", "aunt", "stranger")) {
            for (direction in listOf("ANCESTORS", "DESCENDANTS", "SIDE")) {
                for (depth in listOf(1, 2, null)) {
                    for (partners in listOf(false, true)) {
                        val grant = f.grant(anchor, direction, depth, partners)
                        assertEquals(f.reference(grant), f.scope(grant), "$anchor $direction depth=$depth partners=$partners")
                    }
                }
            }
            val whole = f.grant(anchor, "WHOLE_TREE", partners = true)
            assertEquals(f.reference(whole), f.scope(whole), "$anchor WHOLE_TREE")
        }
    }

    @Test
    fun `cycles in stored data end the recursion at every depth`() = runBlocking {
        val f = newFamily("FT-010 cycle")
        // Bypasses the API (a merge refuses ancestor loops): grandkid recorded as gg1's parent.
        val loop = Uuid.random().toString()
        execute("insert into union_record (id, tree_id, partner1_id, type) values ('$loop', '${f.tree.value}', '${f.id("grandkid")}', 'UNKNOWN')")
        execute("insert into union_child (union_id, child_id, relation, tree_id) values ('$loop', '${f.id("gg1")}', 'BIRTH', '${f.tree.value}')")
        for (direction in listOf("ANCESTORS", "DESCENDANTS", "SIDE")) {
            for (depth in listOf(1, 3, 50, Int.MAX_VALUE, null)) {
                for (partners in listOf(false, true)) {
                    val grant = f.grant("me", direction, depth, partners)
                    assertEquals(f.reference(grant), f.scope(grant), "$direction depth=$depth partners=$partners")
                }
            }
        }
        assertEquals(setOf("me", "kid", "grandkid", "gg1", "gp1", "mum"), f.scope(f.grant("me", "DESCENDANTS")))
        // Through the loop kid and spouse become "ancestors" of me, so the side takes their
        // descendants too; each person still appears once.
        assertEquals(LABELS.toSet() - setOf("gpX", "exSpouse", "stranger"), f.scope(f.grant("me", "SIDE")))
    }

    // --- Tenant isolation ---------------------------------------------------------------

    @Test
    fun `grants, memberships and scope people stay inside one tree`() = runBlocking {
        val a = newFamily("FT-010 tenant A")
        val b = newTree("FT-010 tenant B", treeOwner = other)
        b.person("outsider", actor = other)
        val before = fingerprint(a)

        // Through the service: another tree's person or a non-member is the generic 404.
        assertFailsWith<NotFoundException> {
            GrantService.createGrant(a.tree, owner, CreateGrantRequest(member.value, "VIEWER", b.id("outsider"), "DESCENDANTS"))
        }
        assertFailsWith<NotFoundException> {
            GrantService.createGrant(a.tree, owner, CreateGrantRequest(other.value, "VIEWER", a.id("me"), "DESCENDANTS"))
        }
        assertEquals(before, fingerprint(a))

        // In the database: every reference is same-tree.
        val grant = a.grant("me", "WHOLE_TREE")
        fun insertGrant(tree: String, account: String, anchor: String) = execute(
            "insert into tree_grant (id, tree_id, account_id, role, anchor_person_id, direction, include_partners, created_by_account_id) " +
                "values (gen_random_uuid(), '$tree', '$account', 'VIEWER', '$anchor', 'DESCENDANTS', false, '${owner.value}')",
        )
        assertFailsWith<PSQLException>("anchor from another tree") { insertGrant(a.tree.value, member.value, b.id("outsider")) }
        assertFailsWith<PSQLException>("account without a membership in the tree") { insertGrant(a.tree.value, other.value, a.id("me")) }
        assertFailsWith<PSQLException>("scope person from another tree") {
            execute("insert into grant_scope (grant_id, tree_id, person_id) values ('${grant.id}', '${a.tree.value}', '${b.id("outsider")}')")
        }
        assertFailsWith<PSQLException>("scope row filed under another tree") {
            execute("insert into grant_scope (grant_id, tree_id, person_id) values ('${grant.id}', '${b.tree.value}', '${b.id("outsider")}')")
        }

        // A whole-tree scope has every person of A and nobody from B.
        assertEquals(LABELS.toSet(), a.scope(grant))
        assertEquals(LABELS.toSet(), a.snapshotScope(grant))
        // A's grant can't be resolved for B by ID, through either entry point...
        assertFailsWith<NotFoundException> { GrantScopeService.refreshScope(b.treeUuid, Uuid.parse(grant.id)) }
        assertFailsWith<NotFoundException> { snapshot { GrantScopeService.scopeInSnapshot(b.treeUuid, Uuid.parse(grant.id)) } }
        assertEquals(emptyMap(), snapshot { GrantScopeService.scopesInSnapshot(b.treeUuid, Uuid.parse(member.value)) })
        // ...nor as a definition obtained from another query: a whole-tree grant of A
        // would otherwise cover everyone in B.
        val definition = GrantRepository.findById(a.treeUuid, Uuid.parse(grant.id))!!
        assertFailsWith<IllegalArgumentException> { snapshot { GrantScopeRepository.resolve(b.treeUuid, definition) } }
        assertEquals(0L, count("select count(*) from grant_scope where tree_id = ?", uuid(b.tree.value)))
    }

    // --- Cache consistency --------------------------------------------------------------

    @Test
    fun `a cached scope is rebuilt after every kind of graph change and only then`() = runBlocking {
        val f = newFamily("FT-010 invalidation")
        val grant = f.grant("gp1", "DESCENDANTS", partners = true)
        val base = setOf("gp1", "gp2", "mum", "dad", "me", "spouse", "kid", "grandkid")
        assertEquals(base, f.scope(grant))
        val built = stamp(grant)
        assertEquals("1:${generation(f)}", built)

        // Not a branch change: a profile edit keeps the cache current.
        val kid = PersonRepository.findById(f.treeUuid, Uuid.parse(f.id("kid")))!!
        PersonService.updatePerson(f.tree, PersonId(f.id("kid")), owner, UpdatePersonRequest("Kiddo", sex = "UNKNOWN", isLiving = false, version = kid.version))
        assertEquals(built, stamp(grant))
        assertEquals(base, f.scope(grant))
        assertEquals(built, stamp(grant), "served from the cache")

        // Person create: stale; a new child link: in.
        val g0 = generation(f)
        f.person("baby")
        assertTrue(generation(f) > g0)
        assertEquals(base, f.scope(grant), "an unlinked person is outside the branch")
        val babyUnion = f.union("grandkid", null, "baby")
        assertEquals(base + "baby", f.scope(grant))

        // New union: a descendant's new partner is in.
        f.person("grandkidPartner")
        val partnered = UnionService.updateUnion(
            f.tree, UnionId(babyUnion.id), owner,
            UpdateUnionRequest(f.id("grandkid"), f.id("grandkidPartner"), type = "MARRIED", version = babyUnion.version),
        )
        assertEquals(base + setOf("baby", "grandkidPartner"), f.scope(grant))

        // Union update replacing the partner: the old partner is out, the new one in.
        f.person("otherPartner")
        UnionService.updateUnion(
            f.tree, UnionId(babyUnion.id), owner,
            UpdateUnionRequest(f.id("grandkid"), f.id("otherPartner"), type = "MARRIED", version = partnered.version),
        )
        assertEquals(base + setOf("baby", "otherPartner"), f.scope(grant))

        // Child-link delete, then union delete: out again.
        val link = f.unionOf("grandkid", "otherPartner")
        UnionService.deleteChild(f.tree, UnionId(link.id), PersonId(f.id("baby")), owner, listOf(link.children.single().version.toString()))
        assertEquals(base + "otherPartner", f.scope(grant))
        val emptied = f.unionOf("grandkid", "otherPartner")
        UnionService.deleteUnion(f.tree, UnionId(emptied.id), owner, listOf(emptied.version.toString()))
        assertEquals(base, f.scope(grant))

        // Person delete of someone in a cached scope: not blocked by the cache row, and gone.
        val whole = f.grant("me", "WHOLE_TREE")
        assertTrue("baby" in f.scope(whole))
        val baby = PersonRepository.findById(f.treeUuid, Uuid.parse(f.id("baby")))!!
        PersonService.deletePerson(f.tree, PersonId(f.id("baby")), owner, listOf(baby.version.toString()))
        assertTrue("baby" !in f.scope(whole))
        assertEquals(0L, count("select count(*) from grant_scope where person_id = ?", uuid(f.id("baby"))))
    }

    @Test
    fun `multiple grants union for the member and invalidate independently`() = runBlocking {
        val f = newFamily("FT-010 multiple")
        val mine = f.grant("me", "DESCENDANTS", role = "CONTRIBUTOR")
        val up = f.grant("me", "ANCESTORS", depth = 1, role = "BRANCH_OWNER")
        f.grant("stranger", "DESCENDANTS", holder = owner) // another member's grant
        val scopes = snapshot { GrantScopeService.scopesInSnapshot(f.treeUuid, Uuid.parse(member.value)) }
        assertEquals(setOf(GrantId(mine.id), GrantId(up.id)), scopes.keys)
        assertEquals(setOf("me", "kid", "grandkid", "mum", "dad"), scopes.values.flatten().map { f.label(it.toString()) }.toSet())
        assertNull(stamp(mine), "a snapshot read never writes the cache")

        // Redefining one grant makes only that one stale.
        f.scope(mine)
        f.scope(up)
        val mineStamp = stamp(mine)
        val updated = GrantService.updateGrant(
            f.tree, GrantId(up.id), owner, UpdateGrantRequest("BRANCH_OWNER", f.id("me"), "ANCESTORS", depth = 2, version = up.version),
        )
        assertEquals(2, updated.version)
        assertEquals(setOf("me", "mum", "dad", "gp1", "gp2", "dadParent"), f.scope(updated))
        assertEquals("2:${generation(f)}", stamp(updated))
        assertEquals(mineStamp, stamp(mine))

        // Deleting a grant removes its cache with it.
        GrantService.deleteGrant(f.tree, GrantId(up.id), owner, version = 2)
        assertEquals(0L, count("select count(*) from grant_scope where grant_id = ?", uuid(up.id)))
        assertNull(stamp(updated))
        assertEquals(setOf(GrantId(mine.id)), snapshot { GrantScopeService.scopesInSnapshot(f.treeUuid, Uuid.parse(member.value)) }.keys)
    }

    // --- Snapshot reads -----------------------------------------------------------------

    @Test
    fun `snapshot reads require a transaction with stable isolation`() {
        val treeId = Uuid.random()
        val otherId = Uuid.random()
        assertFailsWith<IllegalStateException> { GrantScopeService.scopeInSnapshot(treeId, otherId) }
        transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED, readOnly = true) {
            assertFailsWith<IllegalStateException> { GrantScopeService.scopesInSnapshot(treeId, otherId) }
        }
    }

    @Test
    fun `a read-only snapshot uses a current cache, computes a stale one without writing, and stays on its snapshot`() = runBlocking {
        val f = newFamily("FT-010 snapshot")
        val grant = f.grant("gp1", "DESCENDANTS")
        val base = setOf("gp1", "mum", "me", "kid", "grandkid")
        assertEquals(base, f.scope(grant))
        val built = stamp(grant)
        assertEquals(base, f.snapshotScope(grant), "cache hit")

        // A graph change makes the cache stale: the snapshot computes the new scope from its
        // own snapshot and leaves the cache untouched (no write in a read-only transaction).
        f.person("late")
        f.union("grandkid", null, "late")
        assertEquals(base + "late", f.snapshotScope(grant))
        assertEquals(built, stamp(grant))

        // Grants and graph come from one snapshot: a change committed after the snapshot
        // started is invisible to it, and visible to the next one.
        f.person("later")
        val unionOfLate = f.unionOf("grandkid", null)
        val (inside, keys) = snapshot {
            val before = GrantScopeService.scopesInSnapshot(f.treeUuid, Uuid.parse(member.value)).keys
            // Committed by other connections while this snapshot is open.
            runBlocking {
                UnionService.addChild(f.tree, UnionId(unionOfLate.id), owner, AddUnionChildRequest(f.id("later"), "BIRTH"))
                f.grant("stranger", "DESCENDANTS")
            }
            GrantScopeService.scopeInSnapshot(f.treeUuid, Uuid.parse(grant.id)) to
                (before to GrantScopeService.scopesInSnapshot(f.treeUuid, Uuid.parse(member.value)).keys)
        }
        assertEquals(base + "late", inside.map { f.label(it.toString()) }.toSet())
        assertEquals(keys.first, keys.second, "a grant created after the snapshot started is not seen")
        assertEquals(base + setOf("late", "later"), f.snapshotScope(grant))
    }

    @Test
    fun `refreshing a grant of a tree that doesn't exist is a clean 404`() = runBlocking {
        assertFailsWith<NotFoundException> { GrantScopeService.refreshScope(Uuid.random(), Uuid.random()) }
        assertFailsWith<NotFoundException> { snapshot { GrantScopeService.scopeInSnapshot(Uuid.random(), Uuid.random()) } }
        Unit
    }

    // --- References and deletes ---------------------------------------------------------

    @Test
    fun `an anchor is a real reference while cache rows never block a delete`() = runBlocking {
        val f = newTree("FT-010 references")
        f.person("anchor")
        f.person("loner")
        val grant = f.grant("anchor", "WHOLE_TREE")
        assertEquals(setOf("anchor", "loner"), f.scope(grant))

        val anchor = PersonRepository.findById(f.treeUuid, Uuid.parse(f.id("anchor")))!!
        val before = fingerprint(f)
        assertFailsWith<ConflictException> {
            PersonService.deletePerson(f.tree, PersonId(f.id("anchor")), owner, listOf(anchor.version.toString()))
        }
        assertEquals(before, fingerprint(f))

        val loner = PersonRepository.findById(f.treeUuid, Uuid.parse(f.id("loner")))!!
        PersonService.deletePerson(f.tree, PersonId(f.id("loner")), owner, listOf(loner.version.toString()))
        assertEquals(setOf("anchor"), f.scope(grant))

        GrantService.deleteGrant(f.tree, GrantId(grant.id), owner, grant.version)
        PersonService.deletePerson(f.tree, PersonId(f.id("anchor")), owner, listOf(anchor.version.toString()))
        assertEquals(0L, count("select count(*) from tree_grant where tree_id = ?", uuid(f.tree.value)))
    }

    @Test
    fun `a merge never moves a grant anchor implicitly and rebuilds scopes from the merged graph`() = runBlocking {
        val f = newTree("FT-010 merge")
        listOf("mum", "dad", "spouse", "child").forEach { f.person(it) }
        f.person("ana", given = "Ana")
        f.person("anaDup", given = "Ana")
        f.union("mum", "dad", "ana", "anaDup")
        f.union("anaDup", "spouse", "child")
        val onDup = f.grant("anaDup", "DESCENDANTS")
        val onAna = f.grant("ana", "DESCENDANTS")
        val family = f.grant("mum", "DESCENDANTS")
        assertEquals(setOf("ana"), f.scope(onAna))
        assertEquals(setOf("mum", "ana", "anaDup", "child"), f.scope(family))

        fun request() = MergePeopleRequest(
            survivorId = f.id("ana"),
            survivorVersion = PersonRepository.findById(f.treeUuid, Uuid.parse(f.id("ana")))!!.version,
            retiredId = f.id("anaDup"),
            retiredVersion = PersonRepository.findById(f.treeUuid, Uuid.parse(f.id("anaDup")))!!.version,
            choices = emptyMap(),
        )
        val before = fingerprint(f)
        val refused = assertFailsWith<ConflictException> { PersonMergeService.merge(f.tree, owner, request()) }
        assertEquals(MERGE_RETIRED_GRANT_ANCHOR_MESSAGE, refused.message)
        assertEquals(before, fingerprint(f))

        // The owner decides: re-anchor the grant explicitly (audited), then merge.
        GrantService.updateGrant(f.tree, GrantId(onDup.id), owner, UpdateGrantRequest("VIEWER", f.id("spouse"), "DESCENDANTS", version = onDup.version))
        PersonMergeService.merge(f.tree, owner, request())

        val survivorGrant = GrantRepository.findById(f.treeUuid, Uuid.parse(onAna.id))!!
        assertEquals(onAna.version, survivorGrant.version, "the survivor's grant itself is unchanged")
        assertEquals(setOf("ana", "child"), f.scope(onAna), "its scope follows the merged relationships")
        assertEquals(setOf("mum", "ana", "child"), f.scope(family))
        assertEquals(0L, count("select count(*) from grant_scope where person_id = ?", uuid(f.id("anaDup"))))
    }

    // --- Grant writes -------------------------------------------------------------------

    @Test
    fun `grant writes are owner only, validated, versioned and audited in their transaction`() = runBlocking {
        val f = newFamily("FT-010 writes")
        val grant = f.grant("me", "DESCENDANTS", depth = 2)
        assertEquals(listOf("CREATE"), grantAudits(grant.id))
        val before = fingerprint(f)

        assertFailsWith<ForbiddenException>("Phase 1: only the owner writes") {
            GrantService.createGrant(f.tree, member, CreateGrantRequest(member.value, "VIEWER", f.id("me"), "DESCENDANTS"))
        }
        // Only unknown names and the Branch depth rule are rejected; no other definition policy.
        for ((direction, depth) in listOf("DESCENDANTS" to 0, "SIDE" to -1, "SIDEWAYS" to null)) {
            assertFailsWith<ValidationException>("$direction depth=$depth") {
                GrantService.createGrant(f.tree, owner, CreateGrantRequest(member.value, "VIEWER", f.id("me"), direction, depth))
            }
        }
        assertFailsWith<ValidationException> {
            GrantService.createGrant(f.tree, owner, CreateGrantRequest(member.value, "OWNER", f.id("me"), "DESCENDANTS"))
        }
        assertFailsWith<ConflictException>("stale version") {
            GrantService.updateGrant(f.tree, GrantId(grant.id), owner, UpdateGrantRequest("VIEWER", f.id("me"), "ANCESTORS", version = 7))
        }
        assertFailsWith<ConflictException>("stale delete") { GrantService.deleteGrant(f.tree, GrantId(grant.id), owner, version = 7) }
        assertFailsWith<NotFoundException> { GrantService.deleteGrant(f.tree, GrantId(Uuid.random().toString()), owner, version = 1) }
        assertEquals(before, fingerprint(f))

        val updated = GrantService.updateGrant(f.tree, GrantId(grant.id), owner, UpdateGrantRequest("CONTRIBUTOR", f.id("mum"), "ANCESTORS", version = 1))
        assertEquals("CONTRIBUTOR" to f.id("mum"), updated.role to updated.anchorPersonId)
        GrantService.deleteGrant(f.tree, GrantId(grant.id), owner, version = updated.version)
        assertEquals(listOf("CREATE", "UPDATE", "DELETE"), grantAudits(grant.id))
    }

    // --- Concurrency --------------------------------------------------------------------

    @Test
    fun `a rebuild waits for an in-flight graph writer and then includes its change`() = runBlocking {
        val f = newFamily("FT-010 rebuild waits")
        val grant = f.grant("gp1", "DESCENDANTS")
        assertEquals(setOf("gp1", "mum", "me", "kid", "grandkid"), f.scope(grant))
        f.person("late") // advances the generation: the cache is stale
        val parents = f.unionOf("me", "spouse")

        // The child link parks on its union's row (its foreign-key check) inside its transaction.
        val read = whileLocked("select 1 from union_record where id = ? for update", parents.id) { release ->
            val add = async { UnionService.addChild(f.tree, UnionId(parents.id), owner, AddUnionChildRequest(f.id("late"), "BIRTH")) }
            awaitBlocked("insert into%union_child")
            val pending = async { f.scope(grant) }
            awaitBlocked("pg_advisory_xact_lock(")
            release()
            withTimeout(10_000) { add.await() }
            withTimeout(10_000) { pending.await() }
        }
        assertTrue("late" in read, read.toString())
        assertEquals("1:${generation(f)}", stamp(grant))
    }

    @Test
    fun `concurrent graph writes, deletes and rebuilds neither deadlock nor leave a stale scope`() = runBlocking {
        val f = newFamily("FT-010 burst")
        val grant = f.grant("me", "DESCENDANTS")
        val whole = f.grant("me", "WHOLE_TREE")
        val newcomers = (1..6).map { f.person("new$it") }
        val doomed = (1..6).map { f.person("doomed$it") }
        val parents = f.unionOf("me", "spouse")
        assertTrue(doomed.all { f.label(it) in f.scope(whole) })

        coroutineScope {
            val writes = newcomers.map { id ->
                async { UnionService.addChild(f.tree, UnionId(parents.id), owner, AddUnionChildRequest(id, "BIRTH")) }
            }
            val deletes = doomed.map { id -> async { PersonService.deletePerson(f.tree, PersonId(id), owner, listOf("1")) } }
            val reads = (1..6).map { i -> async { if (i % 2 == 0) f.scope(grant) else f.scope(whole) } }
            withTimeout(30_000) { (writes + deletes + reads).awaitAll() }
        }
        assertEquals(setOf("me", "kid", "grandkid") + newcomers.map(f::label), f.scope(grant))
        val wholeScope = f.scope(whole)
        assertTrue(doomed.none { f.label(it) in wholeScope } && newcomers.all { f.label(it) in wholeScope }, wholeScope.toString())
    }

    @Test
    fun `a grant on a person being deleted is a clean 404, never a foreign-key failure`() = runBlocking {
        val f = newTree("FT-010 delete race")
        f.person("solo")
        val solo = PersonRepository.findById(f.treeUuid, Uuid.parse(f.id("solo")))!!
        // The delete parks on its audit insert, after its DELETE holds the person.
        whileLocked("lock table audit_log in share mode") { release ->
            val delete = async { PersonService.deletePerson(f.tree, PersonId(f.id("solo")), owner, listOf(solo.version.toString())) }
            awaitBlocked("insert into%audit_log")
            val create = async {
                runCatching { GrantService.createGrant(f.tree, owner, CreateGrantRequest(member.value, "VIEWER", f.id("solo"), "DESCENDANTS")) }
            }
            awaitBlocked("for key share")
            release()
            withTimeout(10_000) { delete.await() }
            val failure = withTimeout(10_000) { create.await() }.exceptionOrNull()
            assertTrue(failure is NotFoundException, "expected 404, got $failure")
        }
        assertEquals(0L, count("select count(*) from tree_grant where tree_id = ?", uuid(f.tree.value)))
        assertEquals(0L, count("select count(*) from audit_log where tree_id = ? and entity_type = 'Grant'", uuid(f.tree.value)))
        assertEquals(1L, count("select count(*) from audit_log where entity_type = 'Person' and action = 'DELETE' and entity_id = ?", f.id("solo")))
    }

    @Test
    fun `a grant that locks its anchor first makes a concurrent person delete a clean 409`() = runBlocking {
        val f = newTree("FT-010 grant first")
        f.person("solo")
        val solo = PersonRepository.findById(f.treeUuid, Uuid.parse(f.id("solo")))!!
        // The grant parks on its audit insert, holding the anchor FOR KEY SHARE; the delete's
        // checks pass (the grant isn't committed) and its DELETE waits on that lock.
        val (created, deleteFailure) = whileLocked("lock table audit_log in share mode") { release ->
            val create = async { GrantService.createGrant(f.tree, owner, CreateGrantRequest(member.value, "VIEWER", f.id("solo"), "DESCENDANTS")) }
            awaitBlocked("insert into%audit_log")
            val delete = async { runCatching { PersonService.deletePerson(f.tree, PersonId(f.id("solo")), owner, listOf(solo.version.toString())) } }
            awaitBlocked("delete from%person")
            release()
            withTimeout(10_000) { create.await() } to withTimeout(10_000) { delete.await() }.exceptionOrNull()
        }
        assertTrue(deleteFailure is ConflictException, "expected 409, got $deleteFailure")
        assertEquals(1L, count("select count(*) from person where id = ?", uuid(f.id("solo"))))
        assertEquals(listOf("CREATE"), grantAudits(created.id))
        assertEquals(0L, count("select count(*) from audit_log where entity_type = 'Person' and action = 'DELETE' and entity_id = ?", f.id("solo")))
    }

    @Test
    fun `a grant created during a merge waits and then sees the retired anchor as missing`() = runBlocking {
        val f = newTree("FT-010 merge race")
        listOf("mum", "dad").forEach { f.person(it) }
        f.person("ana", given = "Ana")
        f.person("anaDup", given = "Ana")
        f.union("mum", "dad", "ana", "anaDup")
        val request = MergePeopleRequest(f.id("ana"), 1, f.id("anaDup"), 1, emptyMap())
        // The merge parks on its first audit insert while holding the tree's advisory lock exclusive.
        whileLocked("lock table audit_log in share mode") { release ->
            val merge = async { PersonMergeService.merge(f.tree, owner, request) }
            awaitBlocked("insert into%audit_log")
            val create = async {
                runCatching { GrantService.createGrant(f.tree, owner, CreateGrantRequest(member.value, "VIEWER", f.id("anaDup"), "DESCENDANTS")) }
            }
            awaitBlocked("pg_advisory_xact_lock_shared")
            release()
            assertEquals(f.id("anaDup"), withTimeout(10_000) { merge.await() }.retiredId)
            val failure = withTimeout(10_000) { create.await() }.exceptionOrNull()
            assertTrue(failure is NotFoundException, "expected 404, got $failure")
        }
        assertEquals(0L, count("select count(*) from tree_grant where tree_id = ?", uuid(f.tree.value)))
        assertTrue(generation(f) > 0)
    }
}
