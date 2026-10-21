// Shared library consumed by services (libs/*). Versions come from the Spring Boot BOM.
plugins {
    id("cabinet.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.spring")
    id("io.spring.dependency-management")
    `java-library`
}

dependencyManagement {
    imports {
        // Without the Spring Boot plugin the BOM would pin Kotlin to its own (older) version
        // and break the Kotlin compiler, so align it with the Kotlin Gradle plugin.
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES) {
            bomProperty("kotlin.version", org.jetbrains.kotlin.gradle.plugin.getKotlinPluginVersion(logger))
        }
    }
}
