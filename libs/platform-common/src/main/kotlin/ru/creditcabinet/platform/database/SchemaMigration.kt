package ru.creditcabinet.platform.database

import org.flywaydb.core.Flyway
import java.sql.DriverManager

/** A short-lived process using the same tested JAR; it never starts the HTTP application. */
object SchemaMigration {
    fun runIfRequested(database: String, environment: Map<String, String> = System.getenv()): Boolean {
        if (environment["MIGRATION_ONLY"] != "true") return false
        require(database in setOf("identity", "credit", "engagement"))
        require(environment["DB_NAME"] == database && environment["DB_USER"] == "${database}_owner") {
            "Migration requires the dedicated owner of its database"
        }
        val host = environment["DB_HOST"] ?: "localhost"
        val port = environment["DB_PORT"] ?: "5432"
        require(host.matches(Regex("[A-Za-z0-9.-]+")) && port.toInt() in 1..65535)
        val password = requireNotNull(environment["DB_PASSWORD"]).also { require(it.isNotBlank()) }
        val url = "jdbc:postgresql://$host:$port/$database"
        val owner = "${database}_owner"
        val flyway = Flyway.configure()
            .dataSource(url, owner, password)
            .locations("classpath:db/migration")
            .placeholders(mapOf("demo-accounts" to (environment["DEMO_ACCOUNTS"] ?: "false")))
            .cleanDisabled(true)
            .load()
        flyway.migrate()
        val permissions = requireNotNull(javaClass.getResourceAsStream("/cabinet/database-permissions.sql"))
            .bufferedReader().use { it.readText() }
            .replace("__DATABASE__", database)
            .replace("__OWNER__", owner)
            .replace("__APP__", "${database}_app")
        DriverManager.getConnection(url, owner, password).use { connection ->
            connection.createStatement().use { it.execute(permissions) }
        }
        println("Schema migration and runtime grants completed for $database")
        return true
    }
}
