plugins { id("cabinet.service-conventions") }
dependencies {
    implementation(project(":libs:servicing-grpc"))
    implementation("io.grpc:grpc-netty-shaded:1.85.1")
    implementation(project(":libs:servicing-persistence"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("io.grpc:grpc-inprocess:1.85.1")
}

tasks.test {
    inputs.property("livePhase", providers.environmentVariable("ALPHA_SERVICING_LIVE_PHASE").orElse("disabled"))
    inputs.property("postgresAcceptance", providers.environmentVariable("ALPHA_SERVICING_TEST_JDBC_URL").orElse("disabled"))
    maxHeapSize = "384m"
}
