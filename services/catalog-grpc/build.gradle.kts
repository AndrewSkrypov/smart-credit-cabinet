plugins {
    id("cabinet.service-conventions")
}

dependencies {
    val libs = versionCatalogs.named("libs")
    implementation(project(":libs:catalog-client"))
    implementation(project(":libs:catalog-jdbc"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("io.grpc:grpc-netty-shaded:1.85.1")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("io.grpc:grpc-inprocess:1.85.1")
    testImplementation(libs.findLibrary("mockito-kotlin").get())
}

springBoot {
    mainClass.set("ru.creditcabinet.cataloggrpc.CatalogGrpcApplicationKt")
}

apply(from = rootProject.file("gradle/catalog-integration.gradle.kts"))
