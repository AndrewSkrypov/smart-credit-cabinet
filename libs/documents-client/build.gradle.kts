plugins { id("cabinet.library-conventions") }
dependencies {
    api(project(":libs:documents-model"))
    api(project(":libs:documents-contracts"))
    implementation("io.grpc:grpc-netty-shaded:1.85.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
