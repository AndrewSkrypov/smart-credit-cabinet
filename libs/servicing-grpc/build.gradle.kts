plugins { id("cabinet.library-conventions") }
dependencies {
    implementation(project(":libs:platform-common"))
    api(project(":libs:servicing-model"))
    api(project(":libs:servicing-contracts"))
    implementation("io.grpc:grpc-netty-shaded:1.85.1")
    implementation("com.google.protobuf:protobuf-java-util:4.33.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
}
