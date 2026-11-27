plugins { id("cabinet.library-conventions") }
dependencies {
    api(project(":libs:servicing-core"))
    implementation("org.springframework:spring-jdbc")
    implementation("org.springframework:spring-context")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
