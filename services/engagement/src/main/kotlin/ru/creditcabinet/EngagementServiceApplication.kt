package ru.creditcabinet

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import ru.creditcabinet.platform.database.SchemaMigration

@SpringBootApplication
class EngagementServiceApplication

fun main(args: Array<String>) {
    if (SchemaMigration.runIfRequested("engagement")) return
    runApplication<EngagementServiceApplication>(*args)
}
