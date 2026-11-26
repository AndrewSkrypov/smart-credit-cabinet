package ru.creditcabinet.assessment.messaging

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature

class InvalidAssessmentEnvelope : RuntimeException()

fun <T> validateAssessmentEnvelope(action: () -> T): T = try {
    action()
} catch (_: JsonProcessingException) {
    throw InvalidAssessmentEnvelope()
} catch (_: IllegalArgumentException) {
    throw InvalidAssessmentEnvelope()
}

class AssessmentJson(mapper: ObjectMapper) {
    @Suppress("DEPRECATION")
    private val strict = mapper.copy()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
            DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
            DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
        )
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)

    fun write(value: Any): String = strict.writeValueAsString(value)

    fun <T> read(payload: String, type: Class<T>): T {
        require(payload.length <= MAX_CHARACTERS) { "Assessment message is too large" }
        val tree = strict.readTree(payload)
        val version = tree.path("protocolVersion")
        require(version.isIntegralNumber && version.canConvertToInt() && version.intValue() == 1) { "Unsupported assessment message version" }
        return strict.treeToValue(tree, type)
    }

    fun <T> readState(payload: String, type: Class<T>): T = strict.readValue(payload, type)

    private companion object {
        const val MAX_CHARACTERS = 1_000_000
    }
}
