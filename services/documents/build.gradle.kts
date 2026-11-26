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
}

val documentsRuntimeTest by tasks.registering(Test::class) {
    description = "Independent Documents own-DB/mTLS/Credit HTTP runtime gate"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/DocumentsRuntimeTest.class")
    useJUnitPlatform()
}
tasks.test { exclude("**/DocumentsRuntimeTest.class") }
