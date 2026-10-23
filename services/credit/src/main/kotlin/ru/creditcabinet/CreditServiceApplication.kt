package ru.creditcabinet

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import ru.creditcabinet.platform.database.SchemaMigration

@SpringBootApplication
class CreditServiceApplication

fun main(args: Array<String>) {
    if (SchemaMigration.runIfRequested("credit")) return
    runApplication<CreditServiceApplication>(*args)
}
