package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.domain.ChildRelation
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.domain.Sex
import com.moxietech.family_tree.domain.UnionType
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.identity.AccountRepository
import com.moxietech.family_tree.server.trees.TreeRepository
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.ClassRule
import org.junit.Test
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Exercises the Person/Union graph against a real Postgres (Testcontainers),
 * per AGENTS.md's "Testcontainers Postgres for repositories" convention.
 * This is the regression test for the recursive-CTE ancestor/descendant
 * bug found during manual end-to-end testing (a self-referencing CTE
 * branch can only reference itself once per Postgres).
 */
class GraphIntegrationTest {

    companion object {
        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        @JvmStatic
        @BeforeClass
        fun setUpDatabase() {
            val dataSource = DatabaseConfig.createDataSource(
                com.moxietech.family_tree.server.config.DatabaseSettings(
                    jdbcUrl = postgres.jdbcUrl,
                    user = postgres.username,
                    password = postgres.password,
                ),
            )
            DatabaseConfig.migrate(dataSource)
            DatabaseConfig.connectExposed(dataSource)
            AppDatabase.init(dataSource)
        }

        @JvmStatic
        @AfterClass
        fun tearDownDatabase() {
            postgres.stop()
        }
    }

    @Test
    fun `ancestors and descendants resolve across a union and its child`() {
        val account = AccountRepository.insert("tester@example.com", "Tester", Locale.EN)
        val tree = TreeRepository.insert("Test Family", account.id)
        val treeUuid = Uuid.parse(tree.id.value)

        val father = PersonRepository.insert(
            treeId = treeUuid,
            givenNames = "Juan",
            surnamePaternal = "Perez",
            surnameMaternal = null,
            sex = Sex.MALE,
            isLiving = false,
            bio = null,
            createdByAccountId = Uuid.parse(account.id.value),
        )
        val mother = PersonRepository.insert(
            treeId = treeUuid,
            givenNames = "Maria",
            surnamePaternal = "Lopez",
            surnameMaternal = null,
            sex = Sex.FEMALE,
            isLiving = false,
            bio = null,
            createdByAccountId = null,
        )
        val child = PersonRepository.insert(
            treeId = treeUuid,
            givenNames = "Ana",
            surnamePaternal = "Perez",
            surnameMaternal = "Lopez",
            sex = Sex.FEMALE,
            isLiving = true,
            bio = null,
            createdByAccountId = null,
        )

        val union = UnionRepository.insert(
            treeId = treeUuid,
            partner1Id = Uuid.parse(father.id.value),
            partner2Id = Uuid.parse(mother.id.value),
            type = UnionType.MARRIED,
            startDate = null,
            endDate = null,
        )
        UnionRepository.addChild(Uuid.parse(union.id.value), Uuid.parse(child.id.value), ChildRelation.BIRTH)

        val ancestorsOfChild = PersonRepository.ancestorsOf(Uuid.parse(child.id.value), treeUuid)
        assertEquals(setOf(father.id, mother.id), ancestorsOfChild.map { it.id }.toSet())

        val descendantsOfFather = PersonRepository.descendantsOf(Uuid.parse(father.id.value), treeUuid)
        assertEquals(listOf(child.id), descendantsOfFather.map { it.id })

        val ancestorsOfFather = PersonRepository.ancestorsOf(Uuid.parse(father.id.value), treeUuid)
        assertTrue(ancestorsOfFather.isEmpty())
    }
}
