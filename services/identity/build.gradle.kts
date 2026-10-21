// identity: accounts, companies, sign-in and tokens. The only service that issues tokens and
// the only one that stores users and passwords (its own database "identity").
plugins {
    id("cabinet.service-conventions")
    id("cabinet.jpa-conventions")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-security")
    testImplementation("org.springframework.security:spring-security-test")
}
