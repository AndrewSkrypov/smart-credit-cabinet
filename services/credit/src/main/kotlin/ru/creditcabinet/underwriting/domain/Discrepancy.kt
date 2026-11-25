package ru.creditcabinet.underwriting.domain

/** The difference between a declared value and the value received from a source. */
data class Discrepancy(val field: String, val declared: String, val source: String, val actual: String, val differencePercent: Int?)
