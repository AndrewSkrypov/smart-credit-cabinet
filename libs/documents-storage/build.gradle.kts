plugins { id("cabinet.library-conventions") }
dependencies {
    api(project(":libs:documents-model"))
    implementation(project(":libs:platform-common"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.aws.sdk.url.connection.client)
}
