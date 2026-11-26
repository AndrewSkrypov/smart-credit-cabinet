plugins {
    id("cabinet.library-conventions")
}

dependencies {
    api(project(":libs:catalog-model"))
    api("org.springframework:spring-jdbc")
    implementation(project(":libs:platform-common"))
}
