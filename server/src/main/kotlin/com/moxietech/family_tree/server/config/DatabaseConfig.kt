package com.moxietech.family_tree.server.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import java.util.concurrent.Executors
import javax.sql.DataSource

/**
 * Reads DB_URL / DB_USER / DB_PASSWORD, defaulting to the docker-compose
 * values for local development. Never hardcode production credentials;
 * production config comes from environment/secrets only (AGENTS.md).
 */
data class DatabaseSettings(
    val jdbcUrl: String = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/family_tree",
    val user: String = System.getenv("DB_USER") ?: "family_tree",
    val password: String = System.getenv("DB_PASSWORD") ?: "family_tree",
)

/**
 * A dedicated thread pool for blocking JDBC/Exposed work, kept off the Ktor
 * event loop (ARCHITECTURE.md / AGENTS.md "Coroutines for all I/O; no
 * blocking calls on request threads").
 */
val DatabaseDispatcher = Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors().coerceAtLeast(4),
) { runnable -> Thread(runnable, "db-worker").apply { isDaemon = true } }.asCoroutineDispatcher()

object DatabaseConfig {

    fun createDataSource(settings: DatabaseSettings = DatabaseSettings()): DataSource {
        val hikariConfig = HikariConfig().apply {
            jdbcUrl = settings.jdbcUrl
            username = settings.user
            password = settings.password
            maximumPoolSize = 10
            poolName = "family-tree-pool"
        }
        return HikariDataSource(hikariConfig)
    }

    /** Runs pending Flyway migrations. Safe to call on every startup — a no-op once applied. */
    fun migrate(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    fun connectExposed(dataSource: DataSource): Database = Database.connect(dataSource)
}

/**
 * Holds the live DataSource for the handful of repository queries that use
 * raw SQL directly (recursive ancestor/descendant CTEs — ARCHITECTURE.md
 * allows reviewed raw SQL for Postgres-specific work) rather than the
 * Exposed DSL. Set once during application startup.
 */
object AppDatabase {
    lateinit var dataSource: DataSource
        private set

    fun init(dataSource: DataSource) {
        this.dataSource = dataSource
    }
}
