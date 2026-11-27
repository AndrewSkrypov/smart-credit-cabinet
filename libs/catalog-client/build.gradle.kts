plugins {
    id("cabinet.library-conventions")
}

dependencies {
    api(project(":libs:catalog-contracts"))
    api(project(":libs:catalog-model"))
    implementation(project(":libs:platform-common"))
    implementation("io.grpc:grpc-netty-shaded:1.85.1")
}
