// Service that owns tables in PostgreSQL: JPA entities plus its own Flyway migrations.
plugins {
    id("org.jetbrains.kotlin.plugin.jpa")
}

dependencies {
    "implementation"("org.springframework.boot:spring-boot-starter-data-jpa")
    "implementation"("org.flywaydb:flyway-core")
    "implementation"("org.flywaydb:flyway-database-postgresql")
    "runtimeOnly"("org.postgresql:postgresql")
}
