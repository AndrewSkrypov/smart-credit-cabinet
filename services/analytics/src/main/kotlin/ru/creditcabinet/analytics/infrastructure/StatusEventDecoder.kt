package ru.creditcabinet.analytics.infrastructure

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import ru.creditcabinet.analytics.domain.ApplicationStatus
import ru.creditcabinet.analytics.domain.StatusEvent
import ru.creditcabinet.analytics.domain.VersionedStatus
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

internal enum class StatusRejection {
    INVALID_PAYLOAD,
    KEY_MISMATCH,
    VERSION_CONFLICT,
    EVENT_ID_CONFLICT,
}

internal sealed interface DecodedStatusEvent {
    data class Valid(val event: StatusEvent) : DecodedStatusEvent
    data class Rejected(val reason: StatusRejection) : DecodedStatusEvent
}

internal class StatusEventDecoder(mapper: ObjectMapper) {
    private val reader = mapper.reader()
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    fun decode(key: String?, payload: String?): DecodedStatusEvent {
        if (payload == null) return invalidPayload
        try {
            val root = reader.readTree(payload) ?: return invalidPayload
            require(root.isObject && root.fieldNames().asSequence().toSet() == fields)

            val applicationId = requireNotNull(root["applicationId"].textValue())
            val version = root["applicationVersion"]
            require(version.isNumber)
            val event = StatusEvent(
                eventId = parseUuid(requireNotNull(root["eventId"].textValue())),
                state = VersionedStatus(
                    applicationId = parseUuid(applicationId),
                    applicationVersion = version.decimalValue().longValueExact(),
                    status = ApplicationStatus.valueOf(requireNotNull(root["status"].textValue())),
                ),
                occurredAt = parseInstant(requireNotNull(root["occurredAt"].textValue())),
            )
            return if (key == applicationId) DecodedStatusEvent.Valid(event) else DecodedStatusEvent.Rejected(StatusRejection.KEY_MISMATCH)
        } catch (_: JsonProcessingException) {
            return invalidPayload
        } catch (_: IllegalArgumentException) {
            return invalidPayload
        } catch (_: ArithmeticException) {
            return invalidPayload
        } catch (_: DateTimeParseException) {
            return invalidPayload
        }
    }

    private fun parseUuid(value: String): UUID = UUID.fromString(value).also {
        require(it.toString() == value)
    }

    private fun parseInstant(value: String): Instant {
        require(timestamp.matches(value) && !value.startsWith("0000-"))
        return Instant.parse(value)
    }

    private companion object {
        val fields = setOf("eventId", "applicationId", "applicationVersion", "status", "occurredAt")
        val timestamp = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]{1,9})?Z")
        val invalidPayload = DecodedStatusEvent.Rejected(StatusRejection.INVALID_PAYLOAD)
    }
}
