package ru.creditcabinet

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import ru.creditcabinet.platform.database.SchemaMigration

@SpringBootApplication
class AnalyticsServiceApplication

fun main(args: Array<String>) {
    if (SchemaMigration.runIfRequested("analytics")) return
    runApplication<AnalyticsServiceApplication>(*args)
}
