// Code every service shares: error model and handler, security baseline, JWT checks, CORS,
// OpenAPI, and cross-service enums. A change here rebuilds and redeploys every service.
plugins {
    id("cabinet.library-conventions")
}

val libs = versionCatalogs.named("libs")

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-security")
    api(libs.findLibrary("springdoc-webmvc-ui").get())
    api(libs.findLibrary("jjwt-api").get())
    // Notifications to engagement are sent after the caller's transaction commits.
    implementation("org.springframework:spring-tx")
    runtimeOnly(libs.findLibrary("jjwt-impl").get())
    runtimeOnly(libs.findLibrary("jjwt-jackson").get())
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
