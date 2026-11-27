plugins { id("cabinet.service-conventions") }
dependencies {
    implementation(project(":libs:documents-client"))
    implementation(project(":libs:documents-storage"))
    implementation(project(":libs:documents-renderer"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("io.grpc:grpc-netty-shaded:1.85.1")
    testImplementation(libs.mockito.kotlin)
}

val documentsRuntimeTest by tasks.registering(Test::class) {
    description = "Independent Documents own-DB/mTLS/Credit HTTP runtime gate"
    // Custom fixture owns its database; the default report depends on an unrelated full test suite.
    setFinalizedBy(emptyList<String>())
    outputs.upToDateWhen { false }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/DocumentsRuntimeTest.class", "**/DocumentsWorkerLeaseRuntimeTest.class")
    useJUnitPlatform()
}
tasks.test { exclude("**/DocumentsRuntimeTest.class", "**/DocumentsWorkerLeaseRuntimeTest.class") }

val documentsRuntimeCoverageReport by tasks.registering(org.gradle.testing.jacoco.tasks.JacocoReport::class) {
    dependsOn(documentsRuntimeTest)
    executionData(fileTree(layout.buildDirectory.dir("jacoco")) { include("test.exec", "documentsRuntimeTest.exec") })
    sourceSets(sourceSets.main.get())
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}
documentsRuntimeTest.configure { finalizedBy(documentsRuntimeCoverageReport) }
