package ru.creditcabinet.assessmentservice

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class AssessmentApplication

fun main(args: Array<String>) {
    runApplication<AssessmentApplication>(*args)
}
