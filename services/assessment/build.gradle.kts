plugins {
    id("cabinet.service-conventions")
}

dependencies {
    implementation(project(":libs:assessment-engine"))
    implementation(project(":libs:assessment-messaging"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
}

// Compile the same pure payment formula into each runtime without a dependency on the Credit application.
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    source(rootProject.file("services/credit/src/main/kotlin/ru/creditcabinet/product/domain/LoanMath.kt"))
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("assessment-kafka")
    }
}

tasks.register<Test>("assessmentKafkaTest") {
    group = "verification"
    description = "Verify Assessment replay against the isolated PostgreSQL and Kafka started by the CI gate"
    val tests = sourceSets.test.get()
    testClassesDirs = tests.output.classesDirs
    classpath = tests.runtimeClasspath
    useJUnitPlatform {
        includeTags("assessment-kafka")
    }
    doNotTrackState("The Kafka broker and database are external to Gradle")
    doFirst {
        require(System.getenv("R2_KAFKA_BOOTSTRAP")?.matches(Regex("127\\.0\\.0\\.1:[0-9]{1,5}")) == true) {
            "Run the Assessment CI gate to provision real Kafka and PostgreSQL"
        }
    }
}
