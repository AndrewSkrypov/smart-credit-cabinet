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
