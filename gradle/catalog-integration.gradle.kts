val integrationClass = mapOf(
    "catalog-grpc" to "ru.creditcabinet.cataloggrpc.CatalogPostgresIntegrationTest",
    "credit" to "ru.creditcabinet.product.RemoteCatalogIntegrationTest",
).getValue(project.name)

tasks.named<Test>("test") {
    filter.excludeTestsMatching(integrationClass)
}

val testSources = extensions.getByType<SourceSetContainer>().named("test")
tasks.register<Test>("catalogIntegrationTest") {
    group = "verification"
    description = "Runs the Catalog integration test with the isolated PostgreSQL and TLS fixtures"
    dependsOn(tasks.named("testClasses"))
    testClassesDirs = files(testSources.map { it.output.classesDirs })
    classpath = files(testSources.map { it.runtimeClasspath })
    useJUnitPlatform()
    filter.includeTestsMatching(integrationClass)
    doNotTrackState("Each invocation uses a disposable PostgreSQL cluster and TLS certificates")
    // The standard JaCoCo report depends on the ordinary test task.
    setFinalizedBy(emptyList<Any>())
}
