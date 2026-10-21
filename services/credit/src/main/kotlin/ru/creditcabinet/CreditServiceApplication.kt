package ru.creditcabinet

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CreditServiceApplication

fun main(args: Array<String>) {
    runApplication<CreditServiceApplication>(*args)
}
