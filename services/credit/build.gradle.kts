// credit: products, applications and their state machine, the credit pipeline (checks,
// enrichment, scoring, decision), offers, signing, disbursement and documents. One service,
// because every step changes the same aggregate (docs/architecture.md). Database "credit".
plugins {
    id("cabinet.service-conventions")
    id("cabinet.jpa-conventions")
}

dependencies {
    implementation(project(":libs:catalog-jdbc"))
    implementation(project(":libs:catalog-client"))
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.aws.sdk.url.connection.client)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.pdfbox) { exclude(group = "commons-logging", module = "commons-logging") }
    implementation(libs.poi.ooxml)
    implementation("org.apache.kafka:kafka-clients")
    testImplementation(libs.mockito.kotlin)
}

tasks.test {
    // Twelve cached pools leave headroom on CI PostgreSQL with max_connections=100.
    systemProperty("spring.test.context.cache.maxSize", "12")
}

apply(from = rootProject.file("gradle/catalog-integration.gradle.kts"))
