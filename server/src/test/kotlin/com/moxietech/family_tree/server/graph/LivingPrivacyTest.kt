package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.AddUnionChildRequest
import com.moxietech.family_tree.api.CreateEventRequest
import com.moxietech.family_tree.api.CreatePersonRequest
import com.moxietech.family_tree.api.CreatePlaceRequest
import com.moxietech.family_tree.api.CreateUnionRequest
import com.moxietech.family_tree.api.PersonDto
import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.domain.MembershipStatus
import com.moxietech.family_tree.domain.PersonId
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.domain.UnionId
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.identity.AccountRepository
import com.moxietech.family_tree.server.trees.MembershipRepository
import com.moxietech.family_tree.server.trees.TreeService
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * FT-066 acceptance: the tree owner sees full details; another active member
 * sees living people as placeholders in every person and branch view, none of
 * their events, and no dates on unions with a living partner, while the
 * graph links needed to draw the tree stay intact. Duplicate candidates never
 * include hidden people.
 *
 * Family (living people marked *):
 *   Abuelo + Abuela (married 1950)  ->  Carmen*
 *   Carmen* + Luis (married 1980)   ->  Sofia*
 */
class LivingPrivacyTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private lateinit var dataSource: HikariDataSource

        private lateinit var owner: Account
        private lateinit var relative: Account
        private lateinit var tree: Tree
        private lateinit var abuelo: String
        private lateinit var abuela: String
        private lateinit var carmen: String
        private lateinit var luis: String
        private lateinit var sofia: String
        private lateinit var grandparentsUnion: String
        private lateinit var parentsUnion: String

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
                tree = TreeService.createTree(owner.id, "Familia López")
                MembershipRepository.insert(
                    Uuid.parse(tree.id.value), Uuid.parse(relative.id.value), personId = null, status = MembershipStatus.ACTIVE,
                )

                abuelo = person("Antonio", "López", isLiving = false)
                abuela = person("Rosa", "Martín", isLiving = false)
                carmen = person("Carmen", "López", isLiving = true, sex = "FEMALE", bio = "Lives in Madrid")
                luis = person("Luis", "Ortega", isLiving = false, sex = "MALE")
                sofia = person("Sofia", "Ortega", isLiving = true)

                grandparentsUnion = UnionService.createUnion(
                    tree.id, owner.id, CreateUnionRequest(abuelo, abuela, type = "MARRIED", startDate = "1950"),
                ).id
                UnionService.addChild(tree.id, UnionId(grandparentsUnion), owner.id, AddUnionChildRequest(carmen, "BIRTH"))
                parentsUnion = UnionService.createUnion(
                    tree.id, owner.id, CreateUnionRequest(carmen, luis, type = "MARRIED", startDate = "1980", endDate = "2010"),
                ).id
                UnionService.addChild(tree.id, UnionId(parentsUnion), owner.id, AddUnionChildRequest(sofia, "BIRTH"))

                val madrid = PlaceService.createPlace(tree.id, owner.id, CreatePlaceRequest("Madrid")).id
                EventService.createEvent(
                    tree.id, owner.id,
                    CreateEventRequest(personId = carmen, type = "BIRTH", date = "12 MAR 1955", placeId = madrid, notes = "Hospital"),
                )
                EventService.createEvent(tree.id, owner.id, CreateEventRequest(personId = luis, type = "DEATH", date = "2010"))
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::dataSource.isInitialized) dataSource.close()
        }

        private suspend fun person(
            givenNames: String,
            surname: String,
            isLiving: Boolean,
            sex: String = "UNKNOWN",
            bio: String? = null,
        ): String = PersonService.createPerson(
            tree.id, owner.id,
            CreatePersonRequest(givenNames = givenNames, surnamePaternal = surname, sex = sex, isLiving = isLiving, bio = bio),
        ).first.id.value
    }

    // --- Owner -------------------------------------------------------------------

    @Test
    fun `the owner sees full details everywhere`() = runBlocking {
        val people = PersonService.listPeople(tree.id, owner.id).associateBy { it.id }
        assertEquals("Carmen López", people.getValue(carmen).fullName)
        assertEquals("Lives in Madrid", people.getValue(carmen).bio)
        assertEquals("FEMALE", people.getValue(carmen).sex)

        val events = EventService.listEventsForPerson(tree.id, PersonId(carmen), owner.id)
        assertEquals("12 MAR 1955", events.single().date?.displayText)
        assertEquals("Madrid", events.single().place?.name)
        assertEquals("Hospital", events.single().notes)

        val parents = UnionService.listUnions(tree.id, owner.id).single { it.id == parentsUnion }
        assertEquals("1980", parents.startDate?.displayText)
        assertEquals("2010", parents.endDate?.displayText)
    }

    // --- Active non-owner --------------------------------------------------------

    @Test
    fun `another member sees living people as placeholders in list and detail`() = runBlocking {
        val people = PersonService.listPeople(tree.id, relative.id).associateBy { it.id }
        assertPlaceholder(people.getValue(carmen))
        assertPlaceholder(people.getValue(sofia))
        assertEquals("Antonio López", people.getValue(abuelo).fullName)
        assertEquals("Luis Ortega", people.getValue(luis).fullName)

        assertPlaceholder(PersonService.getPerson(tree.id, PersonId(carmen), relative.id))
        assertEquals("Rosa Martín", PersonService.getPerson(tree.id, PersonId(abuela), relative.id).fullName)
    }

    @Test
    fun `branch views keep the links but show hidden people as placeholders`() = runBlocking {
        val ancestors = PersonService.ancestorsOf(tree.id, PersonId(sofia), relative.id).associateBy { it.id }
        assertEquals(setOf(carmen, luis, abuelo, abuela), ancestors.keys)
        assertPlaceholder(ancestors.getValue(carmen))
        assertEquals("Luis Ortega", ancestors.getValue(luis).fullName)

        val descendants = PersonService.descendantsOf(tree.id, PersonId(abuelo), relative.id).associateBy { it.id }
        assertEquals(setOf(carmen, sofia), descendants.keys)
        descendants.values.forEach(::assertPlaceholder)
    }

    @Test
    fun `another member gets no events for a hidden person`() = runBlocking {
        assertEquals(emptyList(), EventService.listEventsForPerson(tree.id, PersonId(carmen), relative.id))
        val luisEvents = EventService.listEventsForPerson(tree.id, PersonId(luis), relative.id)
        assertEquals("2010", luisEvents.single().date?.displayText)
    }

    @Test
    fun `unions with a hidden partner lose their dates but keep their links`() = runBlocking {
        val unions = UnionService.listUnions(tree.id, relative.id).associateBy { it.id }

        val parents = unions.getValue(parentsUnion)
        assertNull(parents.startDate)
        assertNull(parents.endDate)
        assertEquals("MARRIED", parents.type)
        assertEquals(carmen, parents.partner1Id)
        assertEquals(luis, parents.partner2Id)
        assertEquals(listOf(sofia), parents.children.map { it.childId })

        // A hidden child doesn't hide the parents' union dates.
        val grandparents = unions.getValue(grandparentsUnion)
        assertEquals("1950", grandparents.startDate?.displayText)
        assertEquals(listOf(carmen), grandparents.children.map { it.childId })
    }

    // --- Duplicate check ---------------------------------------------------------

    @Test
    fun `duplicate candidates exclude people the caller can't see`() = runBlocking {
        val everyone = PersonRepository.listByTree(Uuid.parse(tree.id.value))

        // Policy boundary: a known hidden name produces no candidate, not even a placeholder or score.
        val asRelative = ViewerPolicy(tree, relative.id).duplicateCandidates("Carmen", "López", null, everyone)
        assertEquals(emptyList(), asRelative)
        // ...while a visible near-match still does, so the check itself still works.
        val luisMatch = ViewerPolicy(tree, relative.id).duplicateCandidates("Luis", "Ortega", null, everyone)
        assertEquals(listOf(luis), luisMatch.map { it.person.id })

        val asOwner = ViewerPolicy(tree, owner.id).duplicateCandidates("Carmen", "López", null, everyone)
        assertEquals("Carmen López", asOwner.single().person.fullName)

        // Service boundary: a non-owner can't reach the duplicate check at all in Phase 1 (FT-068)...
        assertFailsWith<ForbiddenException> {
            PersonService.createPerson(tree.id, relative.id, CreatePersonRequest("Carmen", "López", sex = "FEMALE", isLiving = true))
        }
        // ...and the owner still gets the real match.
        val (_, duplicates) = PersonService.createPerson(
            tree.id, owner.id, CreatePersonRequest("Carmen", "Lopez", sex = "FEMALE", isLiving = true),
        )
        assertTrue(duplicates.any { it.person.id == carmen && it.person.fullName == "Carmen López" })
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
        assertNotNull(dto.id)
    }
}
