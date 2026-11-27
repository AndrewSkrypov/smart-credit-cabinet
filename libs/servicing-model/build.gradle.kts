plugins {
    id("cabinet.kotlin-conventions")
    `java-library`
}
version = "1.0.0"

dependencies {
    api(project(":libs:loan-math"))
}
