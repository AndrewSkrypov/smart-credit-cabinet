package ru.creditcabinet

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import ru.creditcabinet.platform.database.SchemaMigration

@SpringBootApplication
class IdentityServiceApplication

fun main(args: Array<String>) {
    if (SchemaMigration.runIfRequested("identity")) return
    runApplication<IdentityServiceApplication>(*args)
}
