plugins {
    id("cabinet.library-conventions")
}

dependencies {
    api(project(":libs:assessment-contracts"))
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("org.springframework:spring-jdbc")
    api("org.apache.kafka:kafka-clients")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
}
