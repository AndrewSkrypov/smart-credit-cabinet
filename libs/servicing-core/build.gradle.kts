plugins {
    id("cabinet.kotlin-conventions")
    `java-library`
}
dependencies {
    api(project(":libs:servicing-model"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.21")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}
