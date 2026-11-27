// credit: products, applications and their state machine, the credit pipeline (checks,
// enrichment, scoring, decision), offers, signing, disbursement and documents. One service,
// because every step changes the same aggregate (docs/architecture.md). Database "credit".
plugins {
    id("cabinet.service-conventions")
    id("cabinet.jpa-conventions")
}

dependencies {
    implementation(project(":libs:loan-math"))
    implementation(project(":libs:documents-client"))
    implementation(project(":libs:documents-storage"))
    implementation(project(":libs:documents-renderer"))
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
    exclude("**/DocumentsClaimFenceRuntimeTest.class", "**/DocumentsArtifactIntentRuntimeTest.class")
    // Twelve cached pools leave headroom on CI PostgreSQL with max_connections=100.
    systemProperty("spring.test.context.cache.maxSize", "12")
}

val documentsClaimFenceRuntimeTest by tasks.registering(Test::class) {
    description = "Real PostgreSQL stale generic worker callback fence gate"
    // Custom fixture owns its database; the default report depends on an unrelated full test suite.
    setFinalizedBy(emptyList<String>())
    outputs.upToDateWhen { false }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/DocumentsClaimFenceRuntimeTest.class", "**/DocumentsArtifactIntentRuntimeTest.class")
    useJUnitPlatform()
}

val documentsCreditRuntimeCoverageReport by tasks.registering(org.gradle.testing.jacoco.tasks.JacocoReport::class) {
    dependsOn(documentsClaimFenceRuntimeTest)
    executionData(fileTree(layout.buildDirectory.dir("jacoco")) { include("test.exec", "documentsClaimFenceRuntimeTest.exec") })
    sourceSets(sourceSets.main.get())
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
documentsClaimFenceRuntimeTest.configure { finalizedBy(documentsCreditRuntimeCoverageReport) }
