plugins {
    id("cabinet.kotlin-conventions")
    `java-library`
}

dependencies {
    api(project(":libs:assessment-contracts"))
}
