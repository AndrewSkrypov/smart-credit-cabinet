package ru.creditcabinet

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ExternalSimApplication

fun main(args: Array<String>) {
    runApplication<ExternalSimApplication>(*args)
}
