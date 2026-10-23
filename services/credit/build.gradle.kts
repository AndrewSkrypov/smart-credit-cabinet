// credit: products, applications and their state machine, the credit pipeline (checks,
// enrichment, scoring, decision), offers, signing, disbursement and documents. One service,
// because every step changes the same aggregate (docs/architecture.md). Database "credit".
plugins {
    id("cabinet.service-conventions")
    id("cabinet.jpa-conventions")
}

dependencies {
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.aws.sdk.url.connection.client)
    implementation(libs.resilience4j.circuitbreaker)
    testImplementation(libs.mockito.kotlin)
}
