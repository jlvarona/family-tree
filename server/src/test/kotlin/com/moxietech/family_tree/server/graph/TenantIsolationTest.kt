package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePlaceRequest
import com.moxietech.family_tree.api.CreateSourceRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.TreeId
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.errors.NotFoundException
import com.moxietech.family_tree.server.identity.AccountRepository
import com.moxietech.family_tree.server.identity.OidcIdentity
import com.moxietech.family_tree.server.sources.SourceService
import com.moxietech.family_tree.server.trees.TreeService
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.postgresql.util.PSQLException
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FT-065 acceptance: a caller authorized for tree A who supplies IDs from
 * tree B gets the same 404 as for an ID that doesn't exist, sees no data
 * from B, and causes no write or audit entry in either tree. Also checks
 * that V2's same-tree foreign keys reject cross-tree rows inserted directly.
 *
 * Runs the services against real Postgres. The same flows over HTTP with
 * test-signed JWTs are in `AuthenticatedAccessTest` (FT-072).
 */
class TenantIsolationTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private lateinit var dataSource: HikariDataSource

        private lateinit var ownerA: Account
        private lateinit var treeA: Tree
        private lateinit var parentA1: String
        private lateinit var parentA2: String
        private lateinit var childA: String
        private lateinit var placeA: String
        private lateinit var unionA: String

        private lateinit var treeB: Tree
        private lateinit var personB1: String
        private lateinit var personB2: String
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

            runBlocking {
                ownerA = AccountRepository.getOrCreateByIdentity(
                    OidcIdentity("https://issuer.test", "owner-a"), "owner-a@example.com", "Owner A", Locale.EN,
                )
                val ownerB = AccountRepository.getOrCreateByIdentity(
                    OidcIdentity("https://issuer.test", "owner-b"), "owner-b@example.com", "Owner B", Locale.EN,
                )
                treeA = TreeService.createTree(ownerA.id, "Family A")
                treeB = TreeService.createTree(ownerB.id, "Family B")

                parentA1 = person(treeA, ownerA, "Juan")
                parentA2 = person(treeA, ownerA, "Maria")
                childA = person(treeA, ownerA, "Ana")
                placeA = PlaceService.createPlace(treeA.id, ownerA.id, CreatePlaceRequest("Sevilla")).id
                unionA = UnionService.createUnion(
                    treeA.id, ownerA.id, CreateUnionRequest(partner1Id = parentA1, partner2Id = parentA2, type = "MARRIED"),
                ).id

                personB1 = person(treeB, ownerB, "Pedro")
                personB2 = person(treeB, ownerB, "Lucia")
                unionB = UnionService.createUnion(
                    treeB.id, ownerB.id, CreateUnionRequest(partner1Id = personB1, partner2Id = personB2, type = "MARRIED"),
                ).id
                placeB = PlaceService.createPlace(treeB.id, ownerB.id, CreatePlaceRequest("Lima")).id
                sourceB = SourceService.createSource(
                    treeB.id, ownerB.id, CreateSourceRequest(title = "Parish register", type = "CHURCH_RECORD"),
                ).id
                EventService.createEvent(
                    treeB.id, ownerB.id,
                    CreateEventRequest(personId = personB1, type = "BIRTH", date = "1901", placeId = placeB, notes = "secret"),
                )
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::dataSource.isInitialized) dataSource.close()
        }

        private suspend fun person(tree: Tree, owner: Account, givenNames: String): String =
            PersonService.createPerson(
                tree.id, owner.id, CreatePersonRequest(givenNames = givenNames, sex = "UNKNOWN", isLiving = false),
            ).first.id.value
    }

    // --- Reads ------------------------------------------------------------------

    @Test
    fun `reading a person from another tree is indistinguishable from a missing person`() {
        val crossTree = assertNotFound { PersonService.getPerson(treeA.id, PersonId(personB1), ownerA.id) }
        val missing = UUID.randomUUID().toString()
        val absent = assertNotFound { PersonService.getPerson(treeA.id, PersonId(missing), ownerA.id) }
        assertEquals(absent.message!!.replace(missing, "<id>"), crossTree.message!!.replace(personB1, "<id>"))
    }

    @Test
    fun `ancestors, descendants and events reject an anchor from another tree`() {
        assertNotFound { PersonService.ancestorsOf(treeA.id, PersonId(personB1), ownerA.id) }
        assertNotFound { PersonService.descendantsOf(treeA.id, PersonId(personB1), ownerA.id) }
        assertNotFound { EventService.listEventsForPerson(treeA.id, PersonId(personB1), ownerA.id) }
    }

    @Test
    fun `tree-wide lists contain only the authorized tree's rows`() = runBlocking {
        val people = PersonService.listPeople(treeA.id, ownerA.id)
        assertTrue(people.all { it.treeId == treeA.id.value })
        assertTrue(people.none { it.id == personB1 || it.id == personB2 })
        assertTrue(UnionService.listUnions(treeA.id, ownerA.id).none { it.id == unionB })
        assertTrue(PlaceService.listPlaces(treeA.id, ownerA.id).none { it.id == placeB })
        assertTrue(SourceService.listSources(treeA.id, ownerA.id).none { it.id == sourceB })
    }

    // --- Writes -----------------------------------------------------------------

    @Test
    fun `writes referencing another tree's rows fail with 404 and change nothing`() {
        val before = rowCounts()

        assertNotFound {
            UnionService.createUnion(treeA.id, ownerA.id, CreateUnionRequest(partner1Id = parentA1, partner2Id = personB1, type = "MARRIED"))
        }
        assertNotFound {
            UnionService.createUnion(treeA.id, ownerA.id, CreateUnionRequest(partner1Id = personB1, type = "UNKNOWN"))
        }
        assertNotFound {
            UnionService.addChild(treeA.id, UnionId(unionB), ownerA.id, AddUnionChildRequest(childId = childA, relation = "BIRTH"))
        }
        assertNotFound {
            EventService.createEvent(treeA.id, ownerA.id, CreateEventRequest(personId = personB1, type = "DEATH"))
        }
        assertNotFound {
            EventService.createEvent(treeA.id, ownerA.id, CreateEventRequest(unionId = unionB, type = "MARRIAGE"))
        }
        assertNotFound {
            EventService.createEvent(treeA.id, ownerA.id, CreateEventRequest(personId = parentA1, type = "BIRTH", placeId = placeB))
        }

        assertEquals(before, rowCounts())
    }

    @Test
    fun `adding a child from another tree to an own union fails and changes nothing`() = runBlocking {
        val unionA = UnionService.createUnion(
            treeA.id, ownerA.id, CreateUnionRequest(partner1Id = parentA1, partner2Id = parentA2, type = "MARRIED"),
        )
        val before = rowCounts()
        assertNotFound {
            UnionService.addChild(treeA.id, UnionId(unionA.id), ownerA.id, AddUnionChildRequest(childId = personB1, relation = "BIRTH"))
        }
        assertEquals(before, rowCounts())
    }

    @Test
    fun `a child link to a missing union is a 404 even when the child id is malformed`() {
        val missingUnion = UUID.randomUUID().toString()
        assertNotFound {
            UnionService.addChild(
                treeA.id, UnionId(missingUnion), ownerA.id, AddUnionChildRequest(childId = "not-a-uuid", relation = "BIRTH"),
            )
        }
    }

    @Test
    fun `same-tree graph traversal and writes still work`() = runBlocking {
        val father = person(treeA, ownerA, "Carlos")
        val mother = person(treeA, ownerA, "Elena")
        val kid = person(treeA, ownerA, "Sofia")
        val union = UnionService.createUnion(
            treeA.id, ownerA.id, CreateUnionRequest(partner1Id = father, partner2Id = mother, type = "MARRIED"),
        )
        val withChild = UnionService.addChild(
            treeA.id, UnionId(union.id), ownerA.id, AddUnionChildRequest(childId = kid, relation = "BIRTH"),
        )
        assertEquals(listOf(kid), withChild.children.map { it.childId })

        val event = EventService.createEvent(
            treeA.id, ownerA.id, CreateEventRequest(personId = kid, type = "BIRTH", date = "1990", placeId = placeA),
        )
        assertEquals(placeA, event.place?.id)
        assertEquals(listOf(event.id), EventService.listEventsForPerson(treeA.id, PersonId(kid), ownerA.id).map { it.id })

        assertEquals(setOf(father, mother), PersonService.ancestorsOf(treeA.id, PersonId(kid), ownerA.id).map { it.id }.toSet())
        assertEquals(listOf(kid), PersonService.descendantsOf(treeA.id, PersonId(father), ownerA.id).map { it.id })
    }

    // --- Database constraints (V2) ---------------------------------------------

    /**
     * One case per same-tree foreign key. Each statement changes exactly one
     * reference to point at tree B and keeps every other reference in tree A,
     * so the only constraint that can reject it is the one named in the case.
     */
    @Test
    fun `direct inserts with cross-tree references violate the specific same-tree foreign key`() {
        val a = treeA.id.value
        data class Case(val name: String, val constraint: String, val sql: String)
        val cases = listOf(
            Case(
                "first partner from another tree", "union_record_partner1_same_tree_fkey",
                "insert into union_record (tree_id, partner1_id, type) values ('$a', '$personB1', 'UNKNOWN')",
            ),
            Case(
                "second partner from another tree", "union_record_partner2_same_tree_fkey",
                "insert into union_record (tree_id, partner1_id, partner2_id, type) " +
                    "values ('$a', '$parentA1', '$personB1', 'MARRIED')",
            ),
            Case(
                "child link to another tree's union", "union_child_union_same_tree_fkey",
                "insert into union_child (tree_id, union_id, child_id, relation) values ('$a', '$unionB', '$childA', 'BIRTH')",
            ),
            Case(
                "child from another tree in a same-tree union", "union_child_child_same_tree_fkey",
                "insert into union_child (tree_id, union_id, child_id, relation) values ('$a', '$unionA', '$personB1', 'BIRTH')",
            ),
            Case(
                "event subject from another tree", "event_person_same_tree_fkey",
                "insert into event (tree_id, person_id, type) values ('$a', '$personB1', 'BIRTH')",
            ),
            Case(
                "event union from another tree", "event_union_same_tree_fkey",
                "insert into event (tree_id, union_id, type) values ('$a', '$unionB', 'MARRIAGE')",
            ),
            Case(
                "event place from another tree", "event_place_same_tree_fkey",
                "insert into event (tree_id, person_id, place_id, type) values ('$a', '$parentA1', '$placeB', 'BIRTH')",
            ),
            Case(
                "membership linked to another tree's person", "membership_person_same_tree_fkey",
                "update membership set person_id = '$personB1' where tree_id = '$a'",
            ),
            Case(
                "citation of another tree's source", "citation_source_same_tree_fkey",
                "insert into citation (tree_id, source_id, target_type, target_id, confidence) " +
                    "values ('$a', '$sourceB', 'PERSON', '$parentA1', 2)",
            ),
        )
        cases.forEach { case ->
            val error = assertFailsWith<PSQLException>(case.name) { execute(case.sql) }
            assertEquals("23503", error.sqlState, "${case.name}: expected a foreign key violation, got ${error.message}")
            assertEquals(case.constraint, error.serverErrorMessage?.constraint, "${case.name}: rejected by the wrong constraint")
        }
    }

    // --- Helpers ----------------------------------------------------------------

    private fun assertNotFound(block: suspend () -> Unit): NotFoundException =
        assertFailsWith<NotFoundException> { runBlocking { block() } }

    /** Row counts of every graph table and the audit log, per tree, to prove a rejected write changed nothing. */
    private fun rowCounts(): Map<String, Long> {
        val tables = listOf("person", "union_record", "union_child", "event", "place", "source", "audit_log")
        return buildMap {
            dataSource.connection.use { connection ->
                for (table in tables) for (tree in listOf(treeA.id, treeB.id)) {
                    connection.prepareStatement("select count(*) from $table where tree_id = ?").use { st ->
                        st.setObject(1, UUID.fromString(tree.value))
                        st.executeQuery().use { rs -> rs.next(); put("$table/${label(tree)}", rs.getLong(1)) }
                    }
                }
            }
        }
    }

    private fun label(tree: TreeId) = if (tree == treeA.id) "A" else "B"

    private fun execute(sql: String) {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }
}
