package com.moxietech.family_tree.server.graph

import com.moxietech.family_tree.api.CreatePlaceRequest
import com.moxietech.family_tree.api.PlaceDto
import com.moxietech.family_tree.domain.Account
import com.moxietech.family_tree.domain.Locale
import com.moxietech.family_tree.domain.Tree
import com.moxietech.family_tree.server.collaboration.AuditJson
import com.moxietech.family_tree.server.config.AppDatabase
import com.moxietech.family_tree.server.config.DatabaseConfig
import com.moxietech.family_tree.server.config.DatabaseSettings
import com.moxietech.family_tree.server.errors.ForbiddenException
import com.moxietech.family_tree.server.errors.ValidationException
import com.moxietech.family_tree.server.identity.AccountRepository
import com.moxietech.family_tree.server.identity.OidcIdentity
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
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FT-069: place creation and its audit entry commit or roll back together. */
class PlaceAuditTest {

    companion object {
        private const val TRIGGER_MESSAGE = "FT-069 test-only trigger rejected the place audit insert"

        @JvmField
        @ClassRule
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        private lateinit var dataSource: HikariDataSource
        private lateinit var owner: Account
        private lateinit var outsider: Account
        private lateinit var tree: Tree

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
                owner = AccountRepository.getOrCreateByIdentity(
                    OidcIdentity("https://issuer.test", "place-owner"), "place-owner@example.com", "Owner", Locale.EN,
                )
                outsider = AccountRepository.getOrCreateByIdentity(
                    OidcIdentity("https://issuer.test", "place-outsider"), "place-outsider@example.com", "Outsider", Locale.EN,
                )
                tree = TreeService.createTree(owner.id, "Places")
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            if (::dataSource.isInitialized) dataSource.close()
        }
    }

    @Test
    fun `a created place has exactly one matching audit snapshot`() = runBlocking {
        val place = PlaceService.createPlace(
            tree.id, owner.id, CreatePlaceRequest(name = "Sevilla", hierarchy = "Andalucía, España", latitude = 37.39, longitude = -5.98),
        )

        dataSource.connection.use { c ->
            c.prepareStatement(
                "select tree_id, actor_account_id, entity_type, action, before_json, after_json " +
                    "from audit_log where entity_id = ?",
            ).use { st ->
                st.setString(1, place.id)
                st.executeQuery().use { rs ->
                    assertTrue(rs.next(), "expected an audit entry for the place")
                    assertEquals(tree.id.value, rs.getString("tree_id"))
                    assertEquals(owner.id.value, rs.getString("actor_account_id"))
                    assertEquals("Place", rs.getString("entity_type"))
                    assertEquals("CREATE", rs.getString("action"))
                    assertNull(rs.getString("before_json"))
                    assertEquals(place, AuditJson.decodeFromString(PlaceDto.serializer(), rs.getString("after_json")))
                    assertFalse(rs.next(), "expected exactly one audit entry for the place")
                }
            }
        }
    }

    @Test
    fun `a rejected request writes neither a place nor an audit entry`() {
        val before = counts()
        assertFailsWith<ValidationException> {
            runBlocking { PlaceService.createPlace(tree.id, owner.id, CreatePlaceRequest(name = "  ")) }
        }
        assertFailsWith<ForbiddenException> {
            runBlocking { PlaceService.createPlace(tree.id, outsider.id, CreatePlaceRequest(name = "Lima")) }
        }
        assertEquals(before, counts())
    }

    @Test
    fun `a failed audit insert rolls back the place`() {
        execute(
            """
            create function fail_place_audit() returns trigger language plpgsql as
            ${'$'}${'$'} begin raise exception '$TRIGGER_MESSAGE'; end ${'$'}${'$'};
            create trigger fail_place_audit before insert on audit_log
              for each row when (new.entity_type = 'Place') execute function fail_place_audit();
            """.trimIndent(),
        )
        try {
            val before = counts()
            val error = assertFails {
                runBlocking { PlaceService.createPlace(tree.id, owner.id, CreatePlaceRequest(name = "Cádiz")) }
            }
            val cause = generateSequence(error) { it.cause }.filterIsInstance<PSQLException>().firstOrNull()
            assertNotNull(cause, "expected a PSQLException in the cause chain, got $error")
            assertEquals("P0001", cause.sqlState)
            assertEquals(TRIGGER_MESSAGE, cause.serverErrorMessage?.message)
            assertEquals(before, counts())
        } finally {
            execute("drop trigger fail_place_audit on audit_log; drop function fail_place_audit();")
        }
    }

    private fun counts(): Pair<Long, Long> = dataSource.connection.use { c ->
        fun count(table: String) = c.prepareStatement("select count(*) from $table where tree_id = ?").use { st ->
            st.setObject(1, UUID.fromString(tree.id.value))
            st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        count("place") to count("audit_log")
    }

    private fun execute(sql: String) {
        dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }
}
