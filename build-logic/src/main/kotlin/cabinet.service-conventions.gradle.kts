// Deployable Spring Boot service (services/*). Every service gets the same web stack,
// health probes and platform-common; its own build file only adds what is specific to it.
plugins {
    id("cabinet.kotlin-conventions")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val libs = versionCatalogs.named("libs")

dependencies {
    "implementation"(project(":libs:platform-common"))
    "implementation"("org.springframework.boot:spring-boot-starter-actuator")
    "implementation"("org.springframework.boot:spring-boot-starter-validation")
    "implementation"("org.springframework.boot:spring-boot-starter-web")
    "implementation"("com.fasterxml.jackson.module:jackson-module-kotlin")
    "implementation"(libs.findLibrary("springdoc-webmvc-ui").get())
    // Metrics for Prometheus (/actuator/prometheus) and a trace id per request in every log
    // line, carried across internal calls.
    "runtimeOnly"("io.micrometer:micrometer-registry-prometheus")
    "implementation"("io.micrometer:micrometer-tracing-bridge-brave")
    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit5")
}

// One predictable artifact name per service: the image build copies build/libs/app.jar.
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("app.jar")
}

tasks.named<Jar>("jar") {
    enabled = false
}
