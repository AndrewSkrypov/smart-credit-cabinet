package ru.creditcabinet.platform.database

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import javax.sql.DataSource

/** Fail startup if a deployed HTTP process accidentally receives schema-owner privileges. */
@Component
class RuntimeDatabaseGuard(private val sources: ObjectProvider<DataSource>, private val environment: Environment) : SmartInitializingSingleton {
    override fun afterSingletonsInstantiated() {
        val source = sources.ifAvailable ?: return
        if ("prod" !in environment.activeProfiles && environment.getProperty("cabinet.database.guard-enabled") != "true") return
        val database = environment.getRequiredProperty("spring.application.name")
        require(database in setOf("identity", "credit", "engagement"))
        require(environment.getProperty("spring.flyway.enabled") == "false") { "Runtime Flyway must be disabled; use the migration process" }
        val sql = JdbcTemplate(source)
        val safe = sql.queryForObject(
            """
            select current_database() = ? and current_user = ?
              and not (r.rolsuper or r.rolcreatedb or r.rolcreaterole or r.rolreplication or r.rolbypassrls)
              and not has_schema_privilege(current_user, 'public', 'CREATE')
              and not has_database_privilege(current_user, current_database(), 'CREATE,TEMP')
              and not exists (select 1 from pg_database d where d.datname <> current_database()
                              and has_database_privilege(current_user, d.oid, 'CONNECT'))
              and not exists (select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
                              where n.nspname='public' and c.relowner=r.oid)
            from pg_roles r where r.rolname=current_user
            """.trimIndent(),
            Boolean::class.java,
            database,
            "${database}_app",
        )
        require(safe == true) { "Runtime database role is not isolated or has administrative privileges" }
    }
}
