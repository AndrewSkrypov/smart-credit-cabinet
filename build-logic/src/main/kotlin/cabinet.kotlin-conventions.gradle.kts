// Base for every JVM module: Kotlin on Java 21, strict nullability for Spring, JUnit 5,
// and ktlint (part of `check`; `./gradlew ktlintFormat` fixes most findings; rules in
// .editorconfig).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
    id("jacoco")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    finalizedBy("jacocoTestReport")
}

jacoco {
    toolVersion = "0.8.15"
}

tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    dependsOn("test")
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

// Security fixes newer than the Spring Boot BOM (found by the image scan in CI). They
// override the BOM's versions for every module; remove each line once Spring Boot
// manages that version or newer.
extra["tomcat.version"] = "10.1.60"
extra["postgresql.version"] = "42.7.13"
extra["httpcore5.version"] = "5.4.4"

