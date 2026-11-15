package com.moxietech.family_tree.server.trees

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePlaceRequest
import com.moxietech.family_tree.api.CreateSourceRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.AccountId
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.graph.EventService
import com.moxietech.family_tree.server.graph.PersonService
import com.moxietech.family_tree.server.graph.PlaceService
import com.moxietech.family_tree.server.graph.UnionService
import com.moxietech.family_tree.server.identity.AccountRepository
import com.moxietech.family_tree.server.sources.SourceService
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FT-068 acceptance: only an ACTIVE membership opens a tree, and during
 * Phase 1 only the active owner may change it. An invited account can't list,
 * read or change the tree; an active non-owner can read and gets 403 for every
 * current mutation, with nothing written or audited.
 *
 * Runs the services against real Postgres. The same flows over HTTP with
 * test-signed JWTs are FT-072's scope.
 */
class MembershipAccessTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private lateinit var dataSource: HikariDataSource

        private lateinit var owner: Account
        private lateinit var relative: Account
        private lateinit var invitee: Account
        private lateinit var tree: Tree
        private lateinit var parent1: String
        private lateinit var parent2: String
        private lateinit var child: String
        private lateinit var union: String

        @JvmStatic
        @BeforeClass
        fun setUp() {
            dataSource = DatabaseConfig.createDataSource(
                DatabaseSettings(jdbcUrl = postgres.jdbcUrl, user = postgres.username, password = postgres.password),
            )
            DatabaseConfig.migrate(dataSource)
            DatabaseConfig.connectExposed(dataSource)
            AppDatabase.init(dataSource)

            runBlocking {
                owner = AccountRepository.insert("owner@example.com", "Owner", Locale.EN)
                relative = AccountRepository.insert("relative@example.com", "Relative", Locale.ES)
                invitee = AccountRepository.insert("invitee@example.com", "Invitee", Locale.EN)
                tree = TreeService.createTree(owner.id, "Family")

                val treeUuid = uuid(tree.id.value)
                MembershipRepository.insert(treeUuid, uuid(relative.id), personId = null, status = MembershipStatus.ACTIVE)
                MembershipRepository.insert(treeUuid, uuid(invitee.id), personId = null, status = MembershipStatus.INVITED)

                parent1 = PersonService.createPerson(tree.id, owner.id, person("Juan")).first.id.value
                parent2 = PersonService.createPerson(tree.id, owner.id, person("Maria")).first.id.value
                child = PersonService.createPerson(tree.id, owner.id, person("Ana")).first.id.value
                union = UnionService.createUnion(
                    tree.id, owner.id, CreateUnionRequest(partner1Id = parent1, partner2Id = parent2, type = "MARRIED"),
                ).id
                UnionService.addChild(tree.id, UnionId(union), owner.id, AddUnionChildRequest(childId = child, relation = "BIRTH"))
                EventService.createEvent(tree.id, owner.id, CreateEventRequest(personId = child, type = "BIRTH", date = "1950"))
                PlaceService.createPlace(tree.id, owner.id, CreatePlaceRequest("Sevilla"))
                SourceService.createSource(tree.id, owner.id, CreateSourceRequest(title = "Parish register", type = "CHURCH_RECORD"))
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::dataSource.isInitialized) dataSource.close()
        }

        private fun uuid(id: AccountId) = kotlin.uuid.Uuid.parse(id.value)
        private fun uuid(id: String) = kotlin.uuid.Uuid.parse(id)
        private fun person(givenNames: String) = CreatePersonRequest(givenNames = givenNames, sex = "UNKNOWN", isLiving = false)
    }

    /** Every read route's service call, as [caller]. */
    private fun reads(caller: AccountId): List<Pair<String, suspend () -> Unit>> = listOf(
        "get tree" to { TreeService.getTreeForReader(tree.id, caller) },
        "list people" to { PersonService.listPeople(tree.id, caller) },
        "get person" to { PersonService.getPerson(tree.id, PersonId(child), caller) },
        "ancestors" to { PersonService.ancestorsOf(tree.id, PersonId(child), caller) },
        "descendants" to { PersonService.descendantsOf(tree.id, PersonId(parent1), caller) },
        "person events" to { EventService.listEventsForPerson(tree.id, PersonId(child), caller) },
        "list unions" to { UnionService.listUnions(tree.id, caller) },
        "list places" to { PlaceService.listPlaces(tree.id, caller) },
        "list sources" to { SourceService.listSources(tree.id, caller) },
    )

    /**
     * Every current mutation under the tree, as [caller]. Each request would
     * succeed for the owner, so a 403 can only come from the write check.
     */
    private fun writes(caller: AccountId): List<Pair<String, suspend () -> Unit>> = listOf(
        "create person" to { PersonService.createPerson(tree.id, caller, person("Pedro")) },
        "create union" to {
            UnionService.createUnion(tree.id, caller, CreateUnionRequest(partner1Id = parent1, partner2Id = child, type = "PARTNERS"))
        },
        "add child" to {
            UnionService.addChild(tree.id, UnionId(union), caller, AddUnionChildRequest(childId = parent1, relation = "ADOPTED"))
        },
        "create event" to { EventService.createEvent(tree.id, caller, CreateEventRequest(personId = parent1, type = "DEATH")) },
        "create place" to { PlaceService.createPlace(tree.id, caller, CreatePlaceRequest("Lima")) },
        "create source" to { SourceService.createSource(tree.id, caller, CreateSourceRequest(title = "Census", type = "CENSUS")) },
    )

    @Test
    fun `an invited account cannot list, read or change the tree`() = runBlocking {
        assertTrue(TreeService.listTreesForAccount(invitee.id).none { it.id == tree.id })
        val before = rowCounts()
        for ((name, call) in reads(invitee.id) + writes(invitee.id)) {
            val error = assertFailsWith<ForbiddenException>(name) { call() }
            assertEquals("Not a member of tree ${tree.id.value}", error.message, name)
        }
        assertEquals(before, rowCounts())
    }

    @Test
    fun `an active non-owner can read the tree`() = runBlocking {
        assertEquals(listOf(tree.id), TreeService.listTreesForAccount(relative.id).map { it.id })
        for ((_, call) in reads(relative.id)) call()
        assertEquals(3, PersonService.listPeople(tree.id, relative.id).size)
        assertEquals(listOf(parent1, parent2).sorted(), PersonService.ancestorsOf(tree.id, PersonId(child), relative.id).map { it.id }.sorted())
    }

    @Test
    fun `an active non-owner gets 403 for every mutation and nothing is written`() = runBlocking {
        val before = rowCounts()
        for ((name, call) in writes(relative.id)) {
            val error = assertFailsWith<ForbiddenException>(name) { call() }
            assertEquals("Only the tree owner can make changes to tree ${tree.id.value}", error.message, name)
        }
        assertEquals(before, rowCounts())
    }

    @Test
    fun `the write check runs before request validation`() = runBlocking {
        // A blank name would be a 400 for the owner; a non-owner learns nothing about the body.
        assertFailsWith<ForbiddenException> {
            PersonService.createPerson(tree.id, relative.id, person("  "))
        }
        assertFailsWith<ForbiddenException> {
            SourceService.createSource(tree.id, relative.id, CreateSourceRequest(title = "x", type = "NOT_A_TYPE"))
        }
        Unit
    }

    @Test
    fun `the active owner can still read and write`() = runBlocking {
        assertTrue(TreeService.listTreesForAccount(owner.id).any { it.id == tree.id })
        for ((_, call) in reads(owner.id)) call()
        val before = rowCounts()
        PlaceService.createPlace(tree.id, owner.id, CreatePlaceRequest("Cádiz"))
        assertEquals(before.getValue("place") + 1, rowCounts().getValue("place"))
    }

    private fun rowCounts(): Map<String, Long> {
        val tables = listOf("person", "union_record", "union_child", "event", "place", "source", "membership", "audit_log")
        return buildMap {
            dataSource.connection.use { connection ->
                for (table in tables) {
                    connection.prepareStatement("select count(*) from $table where tree_id = ?").use { st ->
                        st.setObject(1, UUID.fromString(tree.id.value))
                        st.executeQuery().use { rs -> rs.next(); put(table, rs.getLong(1)) }
                    }
                }
            }
        }
    }
}
