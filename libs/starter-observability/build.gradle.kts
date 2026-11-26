plugins {
    id("cabinet.library-conventions")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    implementation("io.micrometer:micrometer-observation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
}
